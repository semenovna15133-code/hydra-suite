package main

import (
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"sync"
	"time"

	"golang.org/x/net/proxy"
	"golang.org/x/sys/unix"
	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
	"gvisor.dev/gvisor/pkg/waiter"
)

var socksAddr string

// tunEP — свой LinkEndpoint: читает fd через poll+read (проверено sniff-фазой,
// работает под seccomp Android), пишет через unix.Write. Никакого fdbased.
type tunEP struct {
	fd         int
	mu         sync.Mutex
	attached   bool
	dispatcher stack.NetworkDispatcher
}

func (e *tunEP) MTU() uint32                        { return 1500 }
func (e *tunEP) Capabilities() stack.LinkEndpointCapabilities { return 0 }
func (e *tunEP) MaxHeaderLength() uint16            { return 0 }
func (e *tunEP) LinkAddress() tcpip.LinkAddress     { return "" }
func (e *tunEP) GSOMaxSize() uint32                 { return 0 }
func (e *tunEP) IsAttached() bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.attached
}
func (e *tunEP) Wait() {}
func (e *tunEP) ARPHardwareType() header.LinkAddrType {
	return header.LinkAddrTypeNone
}
func (e *tunEP) AddHeader(*stack.PacketBuffer, tcpip.LinkAddress, tcpip.LinkAddress) {}
func (e *tunEP) ParseHeader(*stack.PacketBuffer) header.Header { return nil }

func (e *tunEP) Attach(d stack.NetworkDispatcher) {
	e.mu.Lock()
	e.dispatcher = d
	e.attached = true
	e.mu.Unlock()
	go e.rxLoop()
}

// rxLoop: poll+read — ровно то, что sniff доказал рабочим
func (e *tunEP) rxLoop() {
	buf := make([]byte, 4096)
	pfds := []unix.PollFd{{Fd: int32(e.fd), Events: unix.POLLIN}}
	for {
		if _, err := unix.Poll(pfds, 1000); err != nil {
			continue
		}
		n, err := unix.Read(e.fd, buf)
		if err != nil || n < 1 {
			continue
		}
		var proto tcpip.NetworkProtocolNumber
		switch buf[0] >> 4 {
		case 4:
			proto = ipv4.ProtocolNumber
		default:
			continue // IPv6 и прочее дропаем
		}
		data := make([]byte, n)
		copy(data, buf[:n])
		pb := stack.NewPacketBuffer(stack.PacketBufferOptions{
			Payload: buffer.MakeWithData(data),
		})
		e.dispatcher.DeliverNetworkPacket(proto, pb)
		pb.DecRef()
	}
}

// WritePackets: исходящие от netstack → пишем в TUN fd
func (e *tunEP) WritePackets(pkts stack.PacketBufferList) (int, tcpip.Error) {
	n := 0
	for pkt := pkts.Front(); pkt != nil; pkt = pkt.Next() {
		data := pkt.AsRange().ToView()
		if _, err := unix.Write(e.fd, data); err != nil {
			log.Printf("[hb] tun write: %v", err)
			continue
		}
		n++
	}
	return n, nil
}

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
	log.Printf("[hb] got TUN fd=%d, socks5=%s", fd, socksAddr)

	if err := run(fd); err != nil {
		log.Fatalf("run: %v", err)
	}
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

	const nicID tcpip.NICID = 1
	ep := &tunEP{fd: tunFd}
	if err := s.CreateNIC(nicID, ep); err != nil {
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
	log.Printf("[hb] assigned 10.0.0.5/32, custom tunEP attached")

	s.SetRouteTable([]tcpip.Route{{
		Destination: header.IPv4EmptySubnet,
		NIC:         nicID,
	}})

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
			log.Printf("[hb] socks dial %s: %v", dst, dialErr)
			local.Close()
			return
		}
		log.Printf("[hb] TCP %s:%d -> %s", net.IP(id.RemoteAddress.AsSlice()), id.RemotePort, dst)
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
		log.Printf("[hb] UDP DNS %s -> %s", net.IP(id.RemoteAddress.AsSlice()), dst)
		go splice(local, remote)
	})
	s.SetTransportProtocolHandler(udp.ProtocolNumber, ufwd.HandlePacket)

	log.Printf("[hb] netstack running (custom endpoint)")
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
