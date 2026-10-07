package core

import (
	"bytes"
	"crypto/md5"
	"crypto/tls"
	"encoding/binary"
	"errors"
	"fmt"
	"math/rand"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/gorilla/websocket"
)

type proxySettings struct {
	Server       string   `json:"server"`
	ServerPool   []string `json:"server_pool"`
	Strategy     string   `json:"strategy"`
	Rules        string   `json:"rules"`
	ServerIP     string   `json:"server_ip"`
	Token        string   `json:"token"`
	FallbackAddr string   `json:"fallback_addr,omitempty"`
}

var bufPool = newBufPool(32 * 1024)

func connectNanoTunnel(target string, outboundTag string, payload []byte) (*websocket.Conn, error) {
	settings, ok := getProxySettings(outboundTag)
	if !ok {
		return nil, errors.New("outbound settings not found: " + outboundTag)
	}

	secretKey := settings.Token
	fallback := settings.FallbackAddr

	targetServer := ""
	logLevel := "DIRECT"
	logMsg := ""

	routingMap := getRoutingMap()

	for _, rule := range routingMap {
		if strings.Contains(target, rule.Keyword) {
			targetServer = rule.Node
			logLevel = "RULE"
			logMsg = fmt.Sprintf("规则命中: %-20s → 节点: %s (关键词: %s)", target, targetServer, rule.Keyword)
			break
		}
	}

	if targetServer == "" {
		if len(settings.ServerPool) > 0 {
			poolLen := uint64(len(settings.ServerPool))
			strategy := settings.Strategy
			switch strategy {
			case "rr":
				idx := atomic.AddUint64(&globalRRIndex, 1)
				targetServer = settings.ServerPool[idx%poolLen]
			case "hash":
				h := md5.Sum([]byte(target))
				hashVal := binary.BigEndian.Uint64(h[:8])
				targetServer = settings.ServerPool[hashVal%poolLen]
			default:
				targetServer = settings.ServerPool[rand.Intn(int(poolLen))]
			}
			logLevel = "LB"
			logMsg = fmt.Sprintf("负载均衡: %-25s → 节点: %s (策略: %s)", target, targetServer, strategy)
		} else {
			targetServer = settings.Server
			logLevel = "DIRECT"
			logMsg = fmt.Sprintf("直连访问: %-25s → 节点: %s", target, targetServer)
		}
	}

	emitLog(logLevel, logMsg)

	wsConn, err := dialCleanWebSocket(targetServer, settings.ServerIP, fallback, secretKey)
	if err != nil {
		return nil, err
	}

	if err := sendNanoHeaderV2(wsConn, target, payload, fallback); err != nil {
		wsConn.Close()
		return nil, err
	}

	return wsConn, nil
}

func makePreDialControl() func(network, address string, c syscall.RawConn) error {
	pf := getProtectFunc()
	if pf == nil {
		return nil
	}
	return func(network, address string, c syscall.RawConn) error {
		_ = c.Control(func(fd uintptr) {
			// 尝试 protect，即使部分国产 ROM 返回 false 也不中断拨号，因为系统级已排除本应用
			_ = pf.Protect(int(fd))
		})
		return nil
	}
}

func dialCleanWebSocket(serverAddr, serverIP, fallbackAddr, token string) (*websocket.Conn, error) {
	var sniHost string
	var realIP string
	var realPort string

	cleanServerIP := strings.TrimSpace(serverIP)

	parts := strings.SplitN(serverAddr, "#", 2)
	if len(parts) == 2 {
		sni := strings.TrimSpace(parts[0])
		realAddr := strings.TrimSpace(parts[1])

		sh, sp, err := net.SplitHostPort(sni)
		if err != nil {
			sniHost = sni
			realPort = "443"
		} else {
			sniHost = sh
			realPort = sp
		}

		if cleanServerIP != "" {
			realIP = cleanServerIP
		} else {
			rh, rp, err := net.SplitHostPort(realAddr)
			if err != nil {
				realIP = realAddr
			} else {
				realIP = rh
				realPort = rp
			}
		}
	} else {
		host, port, _, _ := parseServerAddr(serverAddr)
		sniHost = host
		realPort = port
		if cleanServerIP != "" {
			realIP = cleanServerIP
		} else {
			realIP = host
		}
	}

	if strings.Contains(realIP, ":") && !strings.HasPrefix(realIP, "[") {
		if strings.Count(realIP, ":") == 1 {
			h, p, err := net.SplitHostPort(realIP)
			if err == nil {
				realIP = h
				realPort = p
			}
		}
	}

	tlsHost := sniHost
	if strings.HasPrefix(tlsHost, "[") && strings.HasSuffix(tlsHost, "]") {
		tlsHost = tlsHost[1 : len(tlsHost)-1]
	}

	wsURL := buildWsURL(sniHost, realPort, token, fallbackAddr)
	reqHeader := http.Header{}
	reqHeader.Add("Host", tlsHost)
	reqHeader.Add("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
	reqHeader.Add("Authorization", "Bearer "+token)

	netDialer := &net.Dialer{
		Timeout: 8 * time.Second,
		Control: makePreDialControl(),
	}

	dialer := websocket.Dialer{
		TLSClientConfig:  &tls.Config{InsecureSkipVerify: true, ServerName: tlsHost},
		HandshakeTimeout: 10 * time.Second,
		NetDial: func(network, addr string) (net.Conn, error) {
			return netDialer.Dial(network, net.JoinHostPort(realIP, realPort))
		},
	}

	conn, resp, err := dialer.Dial(wsURL, reqHeader)
	if err != nil {
		if resp != nil {
			return nil, fmt.Errorf("HTTP %d (Worker 拒绝)", resp.StatusCode)
		}
		return nil, err
	}
	return conn, nil
}

func sendNanoHeaderV2(wsConn *websocket.Conn, target string, payload []byte, fb string) error {
	host, portStr, _ := net.SplitHostPort(target)
	var port uint16
	fmt.Sscanf(portStr, "%d", &port)

	hostBytes := []byte(host)
	fbBytes := []byte(fb)

	if len(hostBytes) > 255 {
		return errors.New("host length exceeds 255 bytes")
	}
	if len(fbBytes) > 255 {
		return errors.New("fallback address length exceeds 255 bytes")
	}

	buf := new(bytes.Buffer)

	buf.WriteByte(byte(len(hostBytes)))
	buf.Write(hostBytes)

	portBytes := make([]byte, 2)
	binary.BigEndian.PutUint16(portBytes, port)
	buf.Write(portBytes)

	buf.WriteByte(byte(len(fbBytes)))
	if len(fbBytes) > 0 {
		buf.Write(fbBytes)
	}

	if len(payload) > 0 {
		buf.Write(payload)
	}

	return wsConn.WriteMessage(websocket.BinaryMessage, buf.Bytes())
}

func buildWsURL(hostWithPath, port, token, fallbackAddr string) string {
	host := hostWithPath
	path := "/"
	if idx := strings.Index(hostWithPath, "/"); idx != -1 {
		path = hostWithPath[idx:]
		host = hostWithPath[:idx]
	}
	base := fmt.Sprintf("wss://%s:%s%s?token=%s", host, port, path, url.QueryEscape(token))
	if fallbackAddr != "" {
		base += "&pyip=" + url.QueryEscape(fallbackAddr)
	}
	return base
}

func parseServerAddr(addr string) (host, port, path string, err error) {
	path = "/"
	if idx := strings.Index(addr, "/"); idx != -1 {
		path = addr[idx:]
		addr = addr[:idx]
	}
	host, port, err = net.SplitHostPort(addr)
	if err != nil {
		host = addr
		port = "443"
		err = nil
	}
	return
}