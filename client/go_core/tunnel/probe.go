package tunnel

import (
	"net"
	"net/http"
	"sync"
	"time"
)

type NetworkProbeResult struct {
	UdpOk       bool
	TcpOk       bool
	VkApiOk     bool
	DpiDetected bool
	LatencyMs   int64
}

// ProbeNetwork — проверка среды (MANIFEST §7.1), Фаза 2.
// AWG-сервер молчит на мусор, поэтому udpOk = «UDP-пакет ушёл без
// ICMP-ошибки», а реальный latency меряется HealthMonitor'ом через туннель.
func ProbeNetwork(endpoint string, timeoutNs int64) (*NetworkProbeResult, error) {
	result := &NetworkProbeResult{}

	var wg sync.WaitGroup
	wg.Add(2)

	go func() {
		defer wg.Done()
		result.UdpOk = udpSendOk(endpoint, 2*time.Second)
	}()

	go func() {
		defer wg.Done()
		result.VkApiOk = httpGet("https://api.vk.com/method/utils.getServerTime?v=5.131", 2*time.Second)
	}()

	wg.Wait()

	if !result.UdpOk {
		// UDP не уходит совсем → проверяем TCP: жив = признак DPI (L1)
		if tcpConnect("google.com:443", 2*time.Second) {
			result.TcpOk = true
			result.DpiDetected = true
		}
	}
	return result, nil
}

// udpSendOk: dial+write успешны и нет немедленной ICMP-ошибки (ECONNREFUSED).
func udpSendOk(endpoint string, timeout time.Duration) bool {
	conn, err := net.DialTimeout("udp", endpoint, timeout)
	if err != nil {
		return false
	}
	defer conn.Close()
	conn.SetWriteDeadline(time.Now().Add(timeout))
	if _, err := conn.Write([]byte{0x01}); err != nil {
		return false
	}
	// Короткое окно: если прилетит ICMP unreachable — Read вернёт ошибку сразу
	conn.SetReadDeadline(time.Now().Add(300 * time.Millisecond))
	buf := make([]byte, 64)
	_, err = conn.Read(buf)
	if err != nil {
		// timeout = норма (сервер молчит); ошибка = ICMP-отказ
		if netErr, ok := err.(net.Error); ok && netErr.Timeout() {
			return true
		}
		return false
	}
	return true // сервер ответил — тем более ок
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
