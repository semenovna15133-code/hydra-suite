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
)

// SimpleSocksBridge: простой мост без gvisor netstack
// Читает пакеты из TUN, парсит IP/TCP/UDP, форвардит через SOCKS5
type SimpleSocksBridge struct {
	tunFd     int
	socksAddr string
	ctx       context.Context
	cancel    context.CancelFunc
	wg        sync.WaitGroup
}

// NewSimpleSocksBridge создаёт простой мост
func NewSimpleSocksBridge(tunFd int, socksAddr string) *SimpleSocksBridge {
	ctx, cancel := context.WithCancel(context.Background())
	return &SimpleSocksBridge{
		tunFd:     tunFd,
		socksAddr: socksAddr,
		ctx:       ctx,
		cancel:    cancel,
	}
}

// Start запускает чтение из TUN и форвардинг
func (b *SimpleSocksBridge) Start() error {
	b.wg.Add(1)
	go b.readAndForward()
	log.Printf("[SimpleSocksBridge] started, socks=%s", b.socksAddr)
	return nil
}

// readAndForward читает из TUN и форвардит через SOCKS5
func (b *SimpleSocksBridge) readAndForward() {
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
			log.Printf("[SimpleSocksBridge] read error: %v", err)
			continue
		}
		if n == 0 {
			continue
		}

		// Парсим IP заголовок (минимум 20 байт)
		if n < 20 {
			continue
		}

		// IPv4 version + IHL
		version := buf[0] >> 4
		if version != 4 {
			continue // только IPv4
		}

		ihl := int(buf[0]&0x0f) * 4
		if n < ihl {
			continue
		}

		protocol := buf[9]
		srcIP := net.IP(buf[12:16])
		dstIP := net.IP(buf[16:20])

		// Парсим TCP/UDP
		switch protocol {
		case 6: // TCP
			b.forwardTCP(buf[ihl:n], srcIP, dstIP)
		case 17: // UDP
			b.forwardUDP(buf[ihl:n], srcIP, dstIP)
		}
	}
}

// forwardTCP форвардит TCP через SOCKS5
func (b *SimpleSocksBridge) forwardTCP(data []byte, srcIP, dstIP net.IP) {
	if len(data) < 20 {
		return
	}

	srcPort := int(data[0])<<8 | int(data[1])
	dstPort := int(data[2])<<8 | int(data[3])

	// Только SYN пакеты (новые соединения)
	flags := data[13]
	if flags&0x02 == 0 { // не SYN
		return
	}

	log.Printf("[SimpleSocksBridge] TCP SYN %s:%d -> %s:%d", srcIP, srcPort, dstIP, dstPort)

	// Подключаемся к SOCKS5
	dialer, err := proxy.SOCKS5("tcp", b.socksAddr, nil, proxy.Direct)
	if err != nil {
		log.Printf("[SimpleSocksBridge] SOCKS5 dialer error: %v", err)
		return
	}

	dst := fmt.Sprintf("%s:%d", dstIP, dstPort)
	conn, err := dialer.Dial("tcp", dst)
	if err != nil {
		log.Printf("[SimpleSocksBridge] SOCKS5 dial error: %v", err)
		return
	}
	defer conn.Close()

	// TODO: нужно установить TCP соединение обратно в TUN
	// Это сложнее чем с netstack — нужно писать SYN-ACK в TUN fd
	log.Printf("[SimpleSocksBridge] connected to %s via SOCKS5", dst)
}

// forwardUDP форвардит UDP через SOCKS5
func (b *SimpleSocksBridge) forwardUDP(data []byte, srcIP, dstIP net.IP) {
	if len(data) < 8 {
		return
	}

	srcPort := int(data[0])<<8 | int(data[1])
	dstPort := int(data[2])<<8 | int(data[3])

	log.Printf("[SimpleSocksBridge] UDP %s:%d -> %s:%d", srcIP, srcPort, dstIP, dstPort)

	// TODO: реализовать UDP ASSOCIATE
}

// Stop останавливает мост
func (b *SimpleSocksBridge) Stop() {
	b.cancel()
	b.wg.Wait()
	log.Printf("[SimpleSocksBridge] stopped")
}
