package core

import (
	"encoding/binary"
	"fmt"
	"io"
	"net"
)

// handleSOCKS5 完成标准的 RFC 1928 SOCKS5 握手并解析目标地址
func handleSOCKS5(conn net.Conn) (string, error) {
	// 1. 标准协议阶段：读取 VER (版本) 和 NMETHODS (方式数量)，共 2 字节
	buf := make([]byte, 2)
	if _, err := io.ReadFull(conn, buf); err != nil {
		return "", fmt.Errorf("socks5: read ver/nmethods: %w", err)
	}

	ver := buf[0]
	nmethods := int(buf[1])

	if ver != 0x05 {
		return "", fmt.Errorf("socks5: unsupported version: %d", ver)
	}

	// 2. 读取客户端支持的 METHODS 列表 (精确读取 nmethods 个字节)
	methods := make([]byte, nmethods)
	if _, err := io.ReadFull(conn, methods); err != nil {
		return "", fmt.Errorf("socks5: read methods: %w", err)
	}

	// 3. 回复客户端：0x05 (版本5), 0x00 (无需密码认证)
	if _, err := conn.Write([]byte{0x05, 0x00}); err != nil {
		return "", fmt.Errorf("socks5: write auth response: %w", err)
	}

	// 4. 读取客户端请求头：VER(1B), CMD(1B), RSV(1B), ATYP(1B)，共 4 字节
	header := make([]byte, 4)
	if _, err := io.ReadFull(conn, header); err != nil {
		return "", fmt.Errorf("socks5: read request header: %w", err)
	}

	if header[0] != 0x05 {
		return "", fmt.Errorf("socks5: unsupported request version: %d", header[0])
	}
	if header[1] != 0x01 { // 0x01 为 TCP CONNECT 命令
		return "", fmt.Errorf("socks5: unsupported cmd: %d", header[1])
	}

	// 5. 根据 ATYP 读取目标地址
	var host string
	switch header[3] {
	case 0x01: // IPv4 (4 字节)
		b := make([]byte, 4)
		if _, err := io.ReadFull(conn, b); err != nil {
			return "", fmt.Errorf("socks5: read ipv4: %w", err)
		}
		host = net.IP(b).String()

	case 0x03: // 域名 (首字节为域名长度，随后是域名字符串)
		lenBuf := make([]byte, 1)
		if _, err := io.ReadFull(conn, lenBuf); err != nil {
			return "", fmt.Errorf("socks5: read domain len: %w", err)
		}
		domain := make([]byte, lenBuf[0])
		if _, err := io.ReadFull(conn, domain); err != nil {
			return "", fmt.Errorf("socks5: read domain: %w", err)
		}
		host = string(domain)

	case 0x04: // IPv6 (16 字节)
		b := make([]byte, 16)
		if _, err := io.ReadFull(conn, b); err != nil {
			return "", fmt.Errorf("socks5: read ipv6: %w", err)
		}
		host = net.IP(b).String()

	default:
		return "", fmt.Errorf("socks5: unsupported address type: %d", header[3])
	}

	// 6. 读取目标端口 (2 字节大端序)
	portBytes := make([]byte, 2)
	if _, err := io.ReadFull(conn, portBytes); err != nil {
		return "", fmt.Errorf("socks5: read port: %w", err)
	}
	port := binary.BigEndian.Uint16(portBytes)

	return net.JoinHostPort(host, fmt.Sprintf("%d", port)), nil
}

// sendSocks5SuccessResponse 回复 RFC 1928 成功响应 (10 字节)，通知 C 内核链路就绪
func sendSocks5SuccessResponse(conn net.Conn) error {
	resp := []byte{0x05, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00}
	_, err := conn.Write(resp)
	return err
}

// sendSocks5ErrorResponse 回复 SOCKS5 错误
func sendSocks5ErrorResponse(conn net.Conn, rep byte) {
	resp := []byte{0x05, rep, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00}
	_, _ = conn.Write(resp)
}