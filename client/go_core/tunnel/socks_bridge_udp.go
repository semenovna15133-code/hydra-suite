package tunnel

import (
	"fmt"
	"net"

	"golang.org/x/net/proxy"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
)

// ForwardUDP форвардит UDP-пакет через SOCKS5 UDP ASSOCIATE
func (b *SocksBridge) ForwardUDP(pkt *stack.PacketBuffer) error {
	// Парсим UDP заголовок
	udpHdr := header.UDP(pkt.TransportHeader().Slice())
	srcPort := udpHdr.SourcePort()
	dstPort := udpHdr.DestinationPort()

	// Парсим IP заголовок
	ipHdr := header.IPv4(pkt.NetworkHeader().Slice())
	srcIP := net.IP(ipHdr.SourceAddress().AsSlice())
	dstIP := net.IP(ipHdr.DestinationAddress().AsSlice())

	// Получаем payload
	payload := pkt.Payload().AsRange().ToSlice()

	// Подключаемся к SOCKS5 proxy
	dialer, err := proxy.SOCKS5("tcp", b.socksAddr, nil, proxy.Direct)
	if err != nil {
		return fmt.Errorf("SOCKS5 dialer: %v", err)
	}

	// SOCKS5 UDP ASSOCIATE
	conn, err := dialer.Dial("udp", fmt.Sprintf("%s:%d", dstIP, dstPort))
	if err != nil {
		return fmt.Errorf("SOCKS5 dial: %v", err)
	}
	defer conn.Close()

	// Отправляем UDP пакет
	if _, err := conn.Write(payload); err != nil {
		return fmt.Errorf("SOCKS5 write: %v", err)
	}

	// TODO: читать ответ и форвардить обратно в netstack
	// Это требует goroutine для каждого UDP потока

	return nil
}
