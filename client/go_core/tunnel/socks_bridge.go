package tunnel

import (
	"context"
	"fmt"
	"io"
	"log"
	"net"
	"sync"

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

	// Читаем из TUN fd и пишем в link endpoint
	b.wg.Add(1)
	go b.tunToStack()

	// Обрабатываем исходящие пакеты из stack
	b.wg.Add(1)
	go b.stackToSocks()

	log.Printf("[SocksBridge] started, upstream=%s", b.socksAddr)
	return nil
}

// tunToStack читает пакеты из TUN fd и пишет в netstack
func (b *SocksBridge) tunToStack() {
	defer b.wg.Done()
	buf := make([]byte, 1500)
	fd := b.tunFd

	for {
		select {
		case <-b.ctx.Done():
			return
		default:
		}

		n, err := read(fd, buf)
		if err != nil {
			if b.ctx.Err() != nil {
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

// stackToSocks перехватывает исходящие TCP/UDP соединения и форвардит в SOCKS5
func (b *SocksBridge) stackToSocks() {
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
		hdr := header.IPv4(pkt.NetworkHeader().Slice())
		dstIP := net.IP(hdr.DestinationAddress().AsSlice())
		srcIP := net.IP(hdr.SourceAddress().AsSlice())

		// TODO: парсить TCP/UDP заголовки и форвардить в SOCKS5
		// Пока просто логируем
		log.Printf("[SocksBridge] %s -> %s (%d bytes)", srcIP, dstIP, pkt.Size())
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

// read обёртка для системного вызова read
func read(fd int, buf []byte) (int, error) {
	return 0, io.EOF // TODO: реализовать через syscall или unix пакет
}
