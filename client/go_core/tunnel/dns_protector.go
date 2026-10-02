package tunnel

import (
	"fmt"
	"net"

	"golang.org/x/net/proxy"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
)

// DNSProtector перехватывает DNS-запросы и форвардит через SOCKS5
type DNSProtector struct {
	stack     *stack.Stack
	socksAddr string
	dialer    proxy.Dialer
}

// NewDNSProtector создаёт защитник DNS-утечек
func NewDNSProtector(s *stack.Stack, socksAddr string) (*DNSProtector, error) {
	dialer, err := proxy.SOCKS5("tcp", socksAddr, nil, proxy.Direct)
	if err != nil {
		return nil, fmt.Errorf("SOCKS5 dialer: %v", err)
	}

	return &DNSProtector{
		stack:     s,
		socksAddr: socksAddr,
		dialer:    dialer,
	}, nil
}

// InterceptDNS перехватывает DNS-запросы (порт 53) и форвардит через SOCKS5
func (d *DNSProtector) InterceptDNS(pkt *stack.PacketBuffer) error {
	// Парсим UDP заголовок
	udpHdr := header.UDP(pkt.TransportHeader().Slice())
	if udpHdr.DestinationPort() != 53 {
		return fmt.Errorf("not DNS packet")
	}

	// Получаем DNS-запрос
	payload := pkt.Payload().AsRange().ToSlice()

	// Форвардим через SOCKS5
	dstIP := net.IP(header.IPv4(pkt.NetworkHeader().Slice()).DestinationAddress().AsSlice())
	dst := fmt.Sprintf("%s:53", dstIP.String())

	conn, err := d.dialer.Dial("udp", dst)
	if err != nil {
		return fmt.Errorf("SOCKS5 dial: %v", err)
	}
	defer conn.Close()

	// Отправляем запрос
	if _, err := conn.Write(payload); err != nil {
		return fmt.Errorf("SOCKS5 write: %v", err)
	}

	// Читаем ответ
	buf := make([]byte, 4096)
	n, err := conn.Read(buf)
	if err != nil {
		return fmt.Errorf("SOCKS5 read: %v", err)
	}

	// TODO: отправить ответ обратно в netstack
	// Это требует реализации обратного пути (socksToStack)

	return nil
}
