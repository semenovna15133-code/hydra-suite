// hydra-bridge: userspace TCP/IP стек (gVisor netstack) между TUN fd и SOCKS5.
// Получает TUN fd через unix-socket (SCM_RIGHTS) от Kotlin-сервиса.
package main

import (
	"fmt"
	"io"
	"log"
	"net"
	"os"

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
	sockName, socksAddr := os.Args[1], os.Args[2]

	fd, err := recvFd(sockName)
	if err != nil {
		log.Fatalf("recvFd: %v", err)
	}
	log.Printf("[hydra-bridge] got TUN fd=%d, socks5=%s", fd, socksAddr)

	if err := run(fd); err != nil {
		log.Fatalf("run: %v", err)
	}
}

// recvFd: ждём подключение Kotlin-сервиса и принимаем fd через SCM_RIGHTS
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

	linkEP := fdbased.New(&fdbased.Options{
		FDs:  []int{tunFd},
		MTU:  1500,
	})

	const nicID tcpip.NICID = 1
	if err := s.CreateNIC(nicID, linkEP); err != nil {
		return fmt.Errorf("CreateNIC: %v", err)
	}
	s.SetRouteTable([]tcpip.Route{{
		Destination: header.IPv4EmptySubnet,
		NIC:         nicID,
	}})

	// TCP forwarder: каждое новое соединение → SOCKS5
	fwd := tcp.NewForwarder(s, 0, 256, func(r *tcp.ForwarderRequest) {
		id := r.ID()
		var wq waiter.Queue
		ep, err := r.CreateEndpoint(&wq)
		if err != nil {
			r.Complete(true)
			return
		}
		r.Complete(false)
		local := gonet.NewTCPConn(&wq, ep)

		dst := net.JoinHostPort(net.IP(id.LocalAddress.AsSlice()).String(), fmt.Sprint(id.LocalPort))
		remote, err := dialSocks(dst)
		if err != nil {
			log.Printf("[hydra-bridge] socks dial %s: %v", dst, err)
			local.Close()
			return
		}
		log.Printf("[hydra-bridge] TCP %s -> %s", net.IP(id.RemoteAddress.AsSlice()), dst)
		go splice(local, remote)
	})
	s.SetTransportProtocolHandler(tcp.ProtocolNumber, fwd.HandlePacket)

	// UDP: DNS (53) форвардим через SOCKS5 по TCP (DNS-over-TCP к тому же серверу)
	ufwd := udp.NewForwarder(s, func(r *udp.ForwarderRequest) {
		id := r.ID()
		if id.LocalPort != 53 {
			return // остальной UDP пока дропаем
		}
		var wq waiter.Queue
		ep, err := r.CreateEndpoint(&wq)
		if err != nil {
			return
		}
		local := gonet.NewUDPConn(&wq, ep)
		dst := net.JoinHostPort(net.IP(id.LocalAddress.AsSlice()).String(), "53")
		remote, err := dialSocks(dst)
		if err != nil {
			local.Close()
			return
		}
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
