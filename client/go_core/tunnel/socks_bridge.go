package tunnel

import (
	"context"
	"fmt"
	"io"
	"log"
	"net"
	"sync"
	"syscall"

	"golang.org/x/net/proxy"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
	"gvisor.dev/gvisor/pkg/waiter"
)

// SocksBridge перенаправляет трафик из TUN в SOCKS5 upstream
type SocksBridge struct {
	tunFd        int
	socksAddr    string
	stack        *stack.Stack
	linkEndpoint *channel.Endpoint
	ctx          context.Context
	cancel       context.CancelFunc
	wg           sync.WaitGroup
}

// NewSocksBridge создаёт мост между TUN fd и SOCKS5 proxy
func NewSocksBridge(tunFd int, mtu uint32, socksAddr string) *SocksBridge {
	ctx, cancel := context.WithCancel(context.Background())
	return &SocksBridge{
		tunFd:     tunFd,
		socksAddr: socksAddr,
		ctx:       ctx,
		cancel:    cancel,
	}
}

// Start запускает netstack и форвардинг
func (b *SocksBridge) Start() error {
	// Создаём channel-based link endpoint
	linkEP := channel.New(256, 1500, "")
	b.linkEndpoint = linkEP

	// Создаём stack
	s := stack.New(stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol},
	})
	b.stack = s

	// Создаём NIC
	const nicID tcpip.NICID = 1
	if err := s.CreateNIC(nicID, linkEP); err != nil {
		return fmt.Errorf("CreateNIC: %v", err)
	}

	// Добавляем маршрут по умолчанию
	s.SetRouteTable([]tcpip.Route{
		{Destination: header.IPv4EmptySubnet, NIC: nicID},
	})

	// Устанавливаем TCP forwarder
	tcpForwarder := tcp.NewForwarder(s, 0, 1024, b.handleTCPConnection)
	s.SetTransportProtocolHandler(tcp.ProtocolNumber, tcpForwarder.HandlePacket)

	// UDP forwarder (более простой — форвардим все UDP через SOCKS5)
	// TODO: реализовать UDP forwarder аналогично TCP

	// Читаем из TUN fd и пишем в link endpoint
	b.wg.Add(1)
	go b.tunToStack()

	// Обрабатываем исходящие пакеты из stack (для UDP)
	b.wg.Add(1)
	go b.stackToSocksUDP()

	log.Printf("[SocksBridge] started, upstream=%s", b.socksAddr)
	return nil
}

// tunToStack читает пакеты из TUN fd и пишет в netstack
func (b *SocksBridge) tunToStack() {
	defer b.wg.Done()
	buf := make([]byte, 1500)

	for {
		select {
		case <-b.ctx.Done():
			return
		default:
		}

		n, err := syscall.Read(b.tunFd, buf)
		if err != nil {
			if b.ctx.Err() != nil {
				return
			}
			if err == io.EOF {
				return
			}
			log.Printf("[SocksBridge] tun read error: %v", err)
			continue
		}
		if n == 0 {
			continue
		}

		// Пишем в link endpoint
		pkt := stack.NewPacketBuffer(stack.PacketBufferOptions{
			Payload: make([]byte, n),
		})
		copy(pkt.NetworkHeader().Push(n), buf[:n])
		b.linkEndpoint.InjectInbound(header.IPv4ProtocolNumber, pkt)
		pkt.DecRef()
	}
}

// handleTCPConnection обрабатывает новое TCP соединение
func (b *SocksBridge) handleTCPConnection(r *tcp.ForwarderRequest) {
	// Получаем ID соединения
	id := r.ID()
	dstIP := net.IP(id.LocalAddress.AsSlice())
	dstPort := id.LocalPort
	srcIP := net.IP(id.RemoteAddress.AsSlice())
	srcPort := id.RemotePort

	log.Printf("[SocksBridge] TCP %s:%d -> %s:%d", srcIP, srcPort, dstIP, dstPort)

	// Создаём waiter queue для endpoint
	var wq waiter.Queue

	// Создаём endpoint
	ep, err := r.CreateEndpoint(&wq)
	if err != nil {
		log.Printf("[SocksBridge] CreateEndpoint error: %v", err)
		r.Complete(true) // RST
		return
	}

	// Подключаемся к SOCKS5 proxy
	dialer, err := proxy.SOCKS5("tcp", b.socksAddr, nil, proxy.Direct)
	if err != nil {
		log.Printf("[SocksBridge] SOCKS5 dialer error: %v", err)
		ep.Close()
		r.Complete(true)
		return
	}

	// Подключаемся к destination через SOCKS5
	dst := fmt.Sprintf("%s:%d", dstIP, dstPort)
	remoteConn, err := dialer.Dial("tcp", dst)
	if err != nil {
		log.Printf("[SocksBridge] SOCKS5 dial error: %v", err)
		ep.Close()
		r.Complete(true)
		return
	}

	// Конвертируем endpoint в net.Conn
	localConn := gonet.NewTCPConn(&wq, ep)

	// Завершаем forwarder request (SYN-ACK отправлен)
	r.Complete(false)

	// Запускаем двусторонний форвардинг
	go b.forwardTCP(localConn, remoteConn)
}

// forwardTCP форвардит данные между netstack и SOCKS5
func (b *SocksBridge) forwardTCP(localConn, remoteConn net.Conn) {
	defer localConn.Close()
	defer remoteConn.Close()

	done := make(chan struct{}, 2)

	// local -> remote
	go func() {
		io.Copy(remoteConn, localConn)
		done <- struct{}{}
	}()

	// remote -> local
	go func() {
		io.Copy(localConn, remoteConn)
		done <- struct{}{}
	}()

	// Ждём завершения одного из направлений
	<-done
}

// stackToSocksUDP перехватывает UDP пакеты и форвардит через SOCKS5 UDP ASSOCIATE
func (b *SocksBridge) stackToSocksUDP() {
	defer b.wg.Done()

	for {
		select {
		case <-b.ctx.Done():
			return
		default:
		}

		// Читаем пакеты из link endpoint (исходящие)
		pkt := b.linkEndpoint.Read()
		if pkt == nil {
			continue
		}

		// Парсим IP заголовок
		ipHdr := header.IPv4(pkt.NetworkHeader().Slice())
		if ipHdr.TransportProtocol() != header.UDPProtocolNumber {
			pkt.DecRef()
			continue
		}

		// TODO: форвардить UDP через SOCKS5 UDP ASSOCIATE
		// Пока просто логируем
		dstIP := net.IP(ipHdr.DestinationAddress().AsSlice())
		srcIP := net.IP(ipHdr.SourceAddress().AsSlice())
		log.Printf("[SocksBridge] UDP %s -> %s (%d bytes)", srcIP, dstIP, pkt.Size())
		pkt.DecRef()
	}
}

// Stop останавливает мост
func (b *SocksBridge) Stop() {
	b.cancel()
	b.wg.Wait()
	if b.stack != nil {
		b.stack.Close()
	}
	log.Printf("[SocksBridge] stopped")
}
