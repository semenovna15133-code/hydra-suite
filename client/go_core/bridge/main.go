package main

import (
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"time"

	"golang.org/x/net/proxy"
	"golang.org/x/sys/unix"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/fdbased"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
	"gvisor.dev/gvisor/pkg/waiter"
)

var socksAddr string

func main() {
	if len(os.Args) < 3 {
		log.Fatal("usage: hydra-bridge <abstract-socket> <socks5-addr>")
	}
	sockName := os.Args[1]
	socksAddr = os.Args[2]

	fd, err := recvFd(sockName)
	if err != nil {
		log.Fatalf("recvFd: %v", err)
	}
	log.Printf("[hydra-bridge] got TUN fd=%d, socks5=%s", fd, socksAddr)

	// SNIFF: читаем первые 6 пакетов из fd в сыром виде
	sniff(fd, 15*time.Second, 6)

	if err := run(fd); err != nil {
		log.Fatalf("run: %v", err)
	}
}

func sniff(fd int, timeout time.Duration, maxPkts int) {
	if err := unix.SetNonblock(fd, true); err != nil {
		log.Printf("[hydra-bridge] sniff: setnonblock: %v", err)
		return
	}
	epfd, err := unix.EpollCreate1(0)
	if err != nil {
		log.Printf("[hydra-bridge] sniff: epoll: %v", err)
		return
	}
	defer unix.Close(epfd)
	ev := unix.EpollEvent{Events: unix.EPOLLIN, Fd: int32(fd)}
	if err := unix.EpollCtl(epfd, unix.EPOLL_CTL_ADD, fd, &ev); err != nil {
		log.Printf("[hydra-bridge] sniff: ctl: %v", err)
		return
	}
	log.Printf("[hydra-bridge] sniff: waiting %s / %d pkts", timeout, maxPkts)
	deadline := time.Now().Add(timeout)
	buf := make([]byte, 4096)
	got := 0
	for got < maxPkts && time.Now().Before(deadline) {
		events := make([]unix.EpollEvent, 4)
		n, err := unix.EpollWait(epfd, events, 1000)
		if err != nil {
			continue
		}
		for i := 0; i < n; i++ {
			nr, err := unix.Read(fd, buf)
			if err != nil {
				continue
			}
			got++
			show := nr
			if show > 24 {
				show = 24
			}
			log.Printf("[hydra-bridge] sniff pkt#%d len=%d head=% x", got, nr, buf[:show])
		}
	}
	log.Printf("[hydra-bridge] sniff done: %d packets seen", got)
}

func recvFd(name string) (int, error) {
	ln, err := net.Listen("unix", "@"+name)
	if err != nil {
		return 0, err
	}
	defer ln.Close()
	conn, err := ln.Accept()
	if err != nil {
		return 0, err
	}
	defer conn.Close()

	uc := conn.(*net.UnixConn)
	buf := make([]byte, 1)
	oob := make([]byte, 32)
	_, oobn, _, _, err := uc.ReadMsgUnix(buf, oob)
	if err != nil {
		return 0, err
	}
	scms, err := unix.ParseSocketControlMessage(oob[:oobn])
	if err != nil || len(scms) == 0 {
		return 0, fmt.Errorf("no SCM_RIGHTS")
	}
	fds, err := unix.ParseUnixRights(&scms[0])
	if err != nil || len(fds) == 0 {
		return 0, fmt.Errorf("no fds")
	}
	return fds[0], nil
}

func run(tunFd int) error {
	s := stack.New(stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol},
	})

	linkEP, fdbasedErr := fdbased.New(&fdbased.Options{
		FDs:               []int{tunFd},
		MTU:               1500,
		RXChecksumOffload: true,
		TXChecksumOffload: true,
	})
	if fdbasedErr != nil {
		return fmt.Errorf("fdbased.New: %v", fdbasedErr)
	}

	const nicID tcpip.NICID = 1
	if err := s.CreateNIC(nicID, linkEP); err != nil {
		return fmt.Errorf("CreateNIC: %v", err)
	}

	addr := tcpip.ProtocolAddress{
		Protocol: ipv4.ProtocolNumber,
		AddressWithPrefix: tcpip.AddressWithPrefix{
			Address:   tcpip.AddrFrom4([4]byte{10, 0, 0, 5}),
			PrefixLen: 32,
		},
	}
	if err := s.AddProtocolAddress(nicID, addr, stack.AddressProperties{}); err != nil {
		return fmt.Errorf("AddProtocolAddress: %v", err)
	}
	log.Printf("[hydra-bridge] assigned 10.0.0.5/32 to NIC %d", nicID)

	s.SetRouteTable([]tcpip.Route{{
		Destination: header.IPv4EmptySubnet,
		NIC:         nicID,
	}})

	// Минимальный stack stats: только dropped (есть точно)
	go func() {
		for range time.Tick(5 * time.Second) {
			st := s.Stats()
			log.Printf("[hydra-bridge] stack: dropped=%d", st.DroppedPackets.Value())
		}
	}()

	fwd := tcp.NewForwarder(s, 0, 256, func(r *tcp.ForwarderRequest) {
		id := r.ID()
		var wq waiter.Queue
		ep, tcpErr := r.CreateEndpoint(&wq)
		if tcpErr != nil {
			r.Complete(true)
			return
		}
		r.Complete(false)
		local := gonet.NewTCPConn(&wq, ep)

		dst := net.JoinHostPort(net.IP(id.LocalAddress.AsSlice()).String(), fmt.Sprint(id.LocalPort))
		remote, dialErr := dialSocks(dst)
		if dialErr != nil {
			log.Printf("[hydra-bridge] socks dial %s: %v", dst, dialErr)
			local.Close()
			return
		}
		log.Printf("[hydra-bridge] TCP %s:%d -> %s", net.IP(id.RemoteAddress.AsSlice()), id.RemotePort, dst)
		go splice(local, remote)
	})
	s.SetTransportProtocolHandler(tcp.ProtocolNumber, fwd.HandlePacket)

	ufwd := udp.NewForwarder(s, func(r *udp.ForwarderRequest) {
		id := r.ID()
		if id.LocalPort != 53 {
			return
		}
		var wq waiter.Queue
		ep, udpErr := r.CreateEndpoint(&wq)
		if udpErr != nil {
			return
		}
		local := gonet.NewUDPConn(s, &wq, ep)
		dst := net.JoinHostPort(net.IP(id.LocalAddress.AsSlice()).String(), "53")
		remote, dialErr := dialSocks(dst)
		if dialErr != nil {
			local.Close()
			return
		}
		log.Printf("[hydra-bridge] UDP DNS %s -> %s", net.IP(id.RemoteAddress.AsSlice()), dst)
		go splice(local, remote)
	})
	s.SetTransportProtocolHandler(udp.ProtocolNumber, ufwd.HandlePacket)

	log.Printf("[hydra-bridge] netstack running")
	select {}
}

func dialSocks(dst string) (net.Conn, error) {
	d, err := proxy.SOCKS5("tcp", socksAddr, nil, proxy.Direct)
	if err != nil {
		return nil, err
	}
	return d.Dial("tcp", dst)
}

func splice(a, b net.Conn) {
	defer a.Close()
	defer b.Close()
	done := make(chan struct{}, 2)
	go func() { io.Copy(b, a); done <- struct{}{} }()
	go func() { io.Copy(a, b); done <- struct{}{} }()
	<-done
}
