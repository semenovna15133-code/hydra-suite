package tunnel

import (
	"net"
	"net/http"
	"time"
)

// NetworkProbeResult — результат зондирования сети (gomobile-экспортируемый)
type NetworkProbeResult struct {
	UdpOk       bool
	TcpOk       bool
	VkApiOk     bool
	DpiDetected bool
	LatencyMs   int64
}

// ProbeNetwork — быстрая проверка сетевого окружения (MANIFEST §7.1).
// timeoutNs — таймаут в наносекундах (gomobile не транслирует time.Duration).
func ProbeNetwork(endpoint string, timeoutNs int64) (*NetworkProbeResult, error) {
	timeout := time.Duration(timeoutNs)
	result := &NetworkProbeResult{}

	// 1. UDP-прямоток к endpoint (симуляция AWG handshake)
	start := time.Now()
	if udpPing(endpoint, timeout) {
		result.UdpOk = true
		result.LatencyMs = int64(time.Since(start).Milliseconds())
	} else if tcpConnect("google.com:443", timeout) {
		// TCP жив, UDP мёртв → признак DPI (MANIFEST §7.2 L1)
		result.TcpOk = true
		result.DpiDetected = true
	}

	// 2. VK API доступен? (П-03: wdtt в пуле / исключён)
	result.VkApiOk = httpGet("https://api.vk.com/method/utils.getServerTime?v=5.131", timeout)

	return result, nil
}

func udpPing(endpoint string, timeout time.Duration) bool {
	conn, err := net.DialTimeout("udp", endpoint, timeout)
	if err != nil {
		return false
	}
	defer conn.Close()

	conn.SetDeadline(time.Now().Add(timeout))
	if _, err := conn.Write([]byte{0x01}); err != nil {
		return false
	}
	buf := make([]byte, 64)
	conn.SetReadDeadline(time.Now().Add(timeout))
	_, _ = conn.Read(buf)
	return true
}

func tcpConnect(addr string, timeout time.Duration) bool {
	conn, err := net.DialTimeout("tcp", addr, timeout)
	if err != nil {
		return false
	}
	conn.Close()
	return true
}

func httpGet(url string, timeout time.Duration) bool {
	client := &http.Client{Timeout: timeout}
	resp, err := client.Get(url)
	if err != nil {
		return false
	}
	defer resp.Body.Close()
	return resp.StatusCode == 200
}
