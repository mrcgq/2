package core

import (
	"bytes"
	"context"
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

// ---------------------------------------------------------------------------------
// 1. 核心网络选路与分流
// ---------------------------------------------------------------------------------

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

// ---------------------------------------------------------------------------------
// 2. Android 套接字保护回调 (防止流量与 DNS 环回进 TUN 虚拟网卡死锁)
// ---------------------------------------------------------------------------------

func makePreDialControl() func(network, address string, c syscall.RawConn) error {
	pf := getProtectFunc()
	if pf == nil {
		return nil
	}
	return func(network, address string, c syscall.RawConn) error {
		_ = c.Control(func(fd uintptr) {
			_ = pf.Protect(int(fd))
		})
		return nil
	}
}

// ---------------------------------------------------------------------------------
// 3. Android 原生安全 DNS 解析器 (对齐电脑版：直连公网 DNS + 强制 IPv4 优先)
// ---------------------------------------------------------------------------------

func safeLookupIP(host string) ([]net.IP, error) {
	// 如果本身已经是 IP，直接返回
	if ip := net.ParseIP(host); ip != nil {
		return []net.IP{ip}, nil
	}

	// 针对 Android 系统规避 /etc/resolv.conf 缺失问题，使用权威公共 DNS 直连解析
	publicDNS := []string{
		"223.5.5.5:53",   // 阿里公共 DNS (国内网络极速)
		"119.29.29.29:53", // 腾讯公共 DNS
		"1.1.1.1:53",      // Cloudflare DNS (海外网络极速)
		"8.8.8.8:53",      // Google DNS
	}

	var allIPs []net.IP
	var lastErr error

	for _, dnsServer := range publicDNS {
		resolver := &net.Resolver{
			PreferGo: true,
			Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
				d := &net.Dialer{
					Timeout: 2000 * time.Millisecond,
					Control: makePreDialControl(), // 保护 DNS UDP 套接字，坚决不循环进 VPN
				}
				return d.DialContext(ctx, "udp", dnsServer)
			},
		}

		ctx, cancel := context.WithTimeout(context.Background(), 2500*time.Millisecond)
		ips, err := resolver.LookupIP(ctx, "ip", host)
		cancel()

		if err == nil && len(ips) > 0 {
			allIPs = ips
			break
		}
		lastErr = err
	}

	if len(allIPs) == 0 {
		if lastErr != nil {
			return nil, fmt.Errorf("安全 DNS 解析域名 [%s] 失败: %w", host, lastErr)
		}
		return nil, fmt.Errorf("未能解析出有效 IP: %s", host)
	}

	// 强制 IPv4 绝对排在前面，消除移动数据网络的 IPv6 黑洞
	var v4 []net.IP
	var v6 []net.IP
	for _, ip := range allIPs {
		if ip.To4() != nil {
			v4 = append(v4, ip)
		} else {
			v6 = append(v6, ip)
		}
	}

	return append(v4, v6...), nil
}

// ---------------------------------------------------------------------------------
// 4. smartDialTCP 智能连接器 (移植自电脑版成功方案，支持域名/IP/端口并自动重试)
// ---------------------------------------------------------------------------------

func smartDialTCP(targetHostOrIP, defaultPort string, timeout time.Duration) (net.Conn, error) {
	target := strings.TrimSpace(targetHostOrIP)
	host := target
	port := defaultPort

	// 拆解 host 和自定义端口
	if strings.HasPrefix(target, "[") {
		if idx := strings.Index(target, "]"); idx != -1 {
			host = target[1:idx]
			rest := target[idx+1:]
			if strings.HasPrefix(rest, ":") && len(rest) > 1 {
				port = rest[1:]
			}
		}
	} else if strings.Count(target, ":") == 1 {
		if h, p, err := net.SplitHostPort(target); err == nil {
			host = h
			port = p
		}
	} else if strings.Count(target, ":") > 1 {
		host = target
	}

	netDialer := &net.Dialer{
		Timeout: timeout,
		Control: makePreDialControl(),
	}

	// 1. 如果指定的是纯 IP（例如 172.64.229.28），直接建连
	if ip := net.ParseIP(host); ip != nil {
		targetAddr := net.JoinHostPort(host, port)
		return netDialer.Dial("tcp", targetAddr)
	}

	// 2. 如果指定的是优选域名（例如 cf.877774.xyz），执行安全解析
	sortedIPs, err := safeLookupIP(host)
	if err != nil || len(sortedIPs) == 0 {
		// 兜底尝试默认拨号
		targetAddr := net.JoinHostPort(host, port)
		return netDialer.Dial("tcp", targetAddr)
	}

	var lastErr error
	singleTimeout := 3500 * time.Millisecond
	if singleTimeout > timeout {
		singleTimeout = timeout
	}

	// 逐个 IP 尝试建连，任一成功立即返回
	for _, ip := range sortedIPs {
		dialerSingle := &net.Dialer{
			Timeout: singleTimeout,
			Control: makePreDialControl(),
		}
		conn, err := dialerSingle.Dial("tcp", net.JoinHostPort(ip.String(), port))
		if err == nil {
			emitLogSafe("SUCCESS", fmt.Sprintf("优选节点建连成功: %s -> %s:%s", host, ip.String(), port))
			return conn, nil
		}
		lastErr = err
	}

	if lastErr != nil {
		return nil, lastErr
	}
	return netDialer.Dial("tcp", net.JoinHostPort(host, port))
}

// ---------------------------------------------------------------------------------
// 5. WebSocket 隧道拨号核心
// ---------------------------------------------------------------------------------

func dialCleanWebSocket(serverAddr, serverIP, fallbackAddr, token string) (*websocket.Conn, error) {
	var sniHost string
	var realAddr string
	var realPort string

	cleanServerIP := strings.TrimSpace(serverIP)

	parts := strings.SplitN(serverAddr, "#", 2)
	if len(parts) == 2 {
		sni := strings.TrimSpace(parts[0])
		poolTarget := strings.TrimSpace(parts[1])

		sh, sp, err := net.SplitHostPort(sni)
		if err != nil {
			sniHost = sni
			realPort = "443"
		} else {
			sniHost = sh
			realPort = sp
		}

		if cleanServerIP != "" {
			realAddr = cleanServerIP
		} else {
			realAddr = poolTarget
		}
	} else {
		host, port, _, _ := parseServerAddr(serverAddr)
		sniHost = host
		realPort = port
		if cleanServerIP != "" {
			realAddr = cleanServerIP
		} else {
			realAddr = host
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

	dialer := websocket.Dialer{
		TLSClientConfig:  &tls.Config{InsecureSkipVerify: true, ServerName: tlsHost},
		HandshakeTimeout: 10 * time.Second,
		NetDial: func(network, addr string) (net.Conn, error) {
			// ★ 彻底解决 cf.877774.xyz 域名不通的问题：交由智能连接器解析并握手
			return smartDialTCP(realAddr, realPort, 6*time.Second)
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

// ---------------------------------------------------------------------------------
// 6. Nano 协议封包与 URL 组装
// ---------------------------------------------------------------------------------

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