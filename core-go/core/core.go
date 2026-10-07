package core

import (
	"encoding/json"
	"net"
	"os"
	"sync"
)

func init() {
	// 强制纯 Go DNS 解析器，规避 Android CGO 死循环
	os.Setenv("GODEBUG", "netdns=go")
}

var (
	globalMutex     sync.Mutex
	globalRunning   bool
	globalNodeTag   string
	globalLogCb     LogCallback
	globalProtectFn ProtectFunc
	globalRRIndex   uint64
	currentSettings map[string]proxySettings
	currentRouting  []rule
	listener        net.Listener
)

type LogCallback interface {
	OnLog(level, nodeTag, message string)
}

type ProtectFunc interface {
	Protect(fd int) bool
}

func SetLogCallback(cb LogCallback) {
	globalMutex.Lock()
	defer globalMutex.Unlock()
	globalLogCb = cb
}

func SetProtectFunc(pf ProtectFunc) {
	globalMutex.Lock()
	defer globalMutex.Unlock()
	globalProtectFn = pf
}

func SetNodeTag(tag string) {
	globalMutex.Lock()
	defer globalMutex.Unlock()
	globalNodeTag = tag
}

func IsRunning() bool {
	globalMutex.Lock()
	defer globalMutex.Unlock()
	return globalRunning
}

func getProtectFunc() ProtectFunc {
	globalMutex.Lock()
	defer globalMutex.Unlock()
	return globalProtectFn
}

func getProxySettings(tag string) (proxySettings, bool) {
	globalMutex.Lock()
	defer globalMutex.Unlock()
	if currentSettings == nil {
		return proxySettings{}, false
	}
	s, ok := currentSettings[tag]
	return s, ok
}

func getRoutingMap() []rule {
	globalMutex.Lock()
	defer globalMutex.Unlock()
	return currentRouting
}

// emitLog 确保在锁彻底释放后安全发射日志，两头兼顾且绝不死锁
func emitLog(level, msg string) {
	globalMutex.Lock()
	cb := globalLogCb
	tag := globalNodeTag
	globalMutex.Unlock()

	if cb != nil {
		cb.OnLog(level, tag, msg)
	}
}

// 兼容别名
func emitLogSafe(level, msg string) {
	emitLog(level, msg)
}

func Start(configJSON string) string {
	listenAddr, errStr := startInternal(configJSON)
	if errStr != "" {
		emitLog("ERROR", "内核启动失败: "+errStr)
		return errStr
	}

	// 锁在 startInternal 内部已经彻底释放，此处发射日志绝不死锁
	emitLog("SYSTEM", "SOCKS5 引擎已成功监听: "+listenAddr)
	return ""
}

func startInternal(configJSON string) (string, string) {
	globalMutex.Lock()
	defer globalMutex.Unlock()

	if globalRunning {
		return "", "already running"
	}

	var cfg config
	if err := json.Unmarshal([]byte(configJSON), &cfg); err != nil {
		return "", "parse config failed: " + err.Error()
	}

	newSettings := make(map[string]proxySettings)
	for _, ob := range cfg.Outbounds {
		if ob.Protocol == "ech-proxy" {
			var ps proxySettings
			if err := json.Unmarshal(ob.Settings, &ps); err == nil {
				newSettings[ob.Tag] = ps
				if ps.Rules != "" {
					currentRouting = parseRulesString(ps.Rules)
				}
			}
		}
	}
	currentSettings = newSettings

	listenAddr := "127.0.0.1:10808"
	if len(cfg.Inbounds) > 0 && cfg.Inbounds[0].Listen != "" {
		listenAddr = cfg.Inbounds[0].Listen
	}

	l, err := net.Listen("tcp", listenAddr)
	if err != nil {
		return "", "listen failed: " + err.Error()
	}
	listener = l
	globalRunning = true

	go func() {
		for {
			conn, err := l.Accept()
			if err != nil {
				break
			}
			go handleIncoming(conn)
		}
	}()

	return listenAddr, ""
}

func handleIncoming(conn net.Conn) {
	defer conn.Close()

	// 1. 协商 SOCKS5
	target, err := handleSOCKS5(conn)
	if err != nil {
		emitLog("ERROR", "SOCKS5 握手失败: "+err.Error())
		sendSocks5ErrorResponse(conn, 0x01)
		return
	}

	// 2. 连接远程 Cloudflare Worker
	wsConn, err := connectNanoTunnel(target, "proxy", nil)
	if err != nil {
		emitLog("ERROR", "连接远程节点失败: "+err.Error())
		sendSocks5ErrorResponse(conn, 0x04)
		return
	}
	defer wsConn.Close()

	// 3. 回复 SOCKS5 成功响应包，彻底打通隧道
	if err := sendSocks5SuccessResponse(conn); err != nil {
		emitLog("ERROR", "回复 SOCKS5 响应失败: "+err.Error())
		return
	}

	// 4. 双向传输数据
	pipeDirect(conn, wsConn)
}

func Stop() {
	stopped := false
	globalMutex.Lock()
	if globalRunning {
		globalRunning = false
		if listener != nil {
			listener.Close()
			listener = nil
		}
		stopped = true
	}
	globalMutex.Unlock()

	if stopped {
		emitLog("SYSTEM", "引擎已停止")
	}
}