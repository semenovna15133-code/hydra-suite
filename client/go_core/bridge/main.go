package main

import (
	"gvisor.dev/gvisor/pkg/waiter"
	"encoding/binary"
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
)

var (
	socksAddr     string
	tunFd         int
	statsFilePath = "/data/user/0/dev.hydra.hydra_client/files/hb_stats.json"
	netStack      *stack.Stack
)

var (
	statsMu      sync.Mutex
	rxBytes      int64
	txBytes      int64
	sessionStart time.Time
)

func initStats() {
	sessionStart = time.Now()
	go func() {
		for {
			time.Sleep(2 * time.Second)
			statsMu.Lock()
			rx, tx := rxBytes, txBytes
			ms := int64(time.Since(sessionStart).Milliseconds())
			statsMu.Unlock()
			data := fmt.Sprintf("{\"rx_bytes\":%d,\"tx_bytes\":%d,\"session_ms\":%d}", rx, tx, ms)
			if err := os.WriteFile(statsFilePath, []byte(data), 0666); err != nil {
				log.Printf("[hb] stats write ERROR: %v (path=%s)", err, statsFilePath)
			} else {
				log.Printf("[hb] stats WRITTEN to %s", statsFilePath)
			}
			log.Printf("[hb] stats rx_bytes=%d tx_bytes=%d session_ms=%d", rx, tx, ms)
		}
	}()
}

func addRx(n int) { statsMu.Lock(); rxBytes += int64(n); statsMu.Unlock() }
func addTx(n int) { statsMu.Lock(); txBytes += int64(n); statsMu.Unlock() }

// tunEndpoint: минимальный LinkEndpoint поверх TUN fd
type tunEndpoint struct {
	fd   int
	mtu  uint32
	mu   sync.RWMutex
	disp stack.NetworkDispatcher
}

func (e *tunEndpoint) MTU() uint32                             { return e.mtu }
func (e *tunEndpoint) MaxHeaderLength() uint16                 { return 0 }
func (e *tunEndpoint) LinkAddress() tcpip.LinkAddress          { return "" }
func (e *tunEndpoint) Capabilities() stack.LinkEndpointCapabilities { return 0 }
func (e *tunEndpoint) Attach(d stack.NetworkDispatcher) {
	e.mu.Lock()
	e.disp = d
	e.mu.Unlock()
}
func (e *tunEndpoint) IsAttached() bool {
	e.mu.RLock()
	defer e.mu.RUnlock()
	return e.disp != nil
}
func (e *tunEndpoint) Wait()                                  {}
func (e *tunEndpoint) ARPHardwareType() header.ARPHardwareType { return header.ARPHardwareNone }
func (e *tunEndpoint) AddHeader(stack.PacketBufferPtr)         {}
func (e *tunEndpoint) ParseHeader(stack.PacketBufferPtr) bool  { return true }

// WritePackets: stack -> TUN (доказанный примитив unix.Write)
func (e *tunEndpoint) WritePackets(pkts stack.PacketBufferList) (int, tcpip.Error) {
	n := 0
	for _, pkt := range pkts.AsSlice() {
		v := pkt.ToView()
		b := v.AsSlice()
		if len(b) > 0 {
			unix.Write(e.fd, b)
			addTx(len(b))
		}
		v.Release()
		n++
	}
	return n, nil
}

// pump: TUN -> stack (доказанный примитив poll+read)
func (e *tunEndpoint) pump() {
	buf := make([]byte, 65536)
	pfds := []unix.PollFd{{Fd: int32(e.fd), Events: unix.POLLIN}}
	var addrSet sync.Once

	for {
		if _, err := unix.Poll(pfds, 1000); err != nil {
			continue
		}
		n, err := unix.Read(e.fd, buf)
		if err != nil || n < 20 {
			continue
		}
		addRx(n)

		var proto tcpip.NetworkProtocolNumber
		switch buf[0] >> 4 {
		case 4:
			proto = ipv4.ProtocolNumber
		default:
			continue
		}

		// локальный адрес TUN узнаём из первого пакета (до delivery!)
		addrSet.Do(func() {
			var a [4]byte
			copy(a[:], buf[12:16])
			netStack.AddProtocolAddress(1, tcpip.ProtocolAddress{
				Protocol: ipv4.ProtocolNumber,
				AddressWithPrefix: tcpip.AddressWithPrefix{
					Address:   tcpip.AddrFrom4(a),
					PrefixLen: 32,
				},
			}, stack.AddressProperties{})
			log.Printf("[hb] netstack local addr %d.%d.%d.%d/32", a[0], a[1], a[2], a[3])
		})

		pkt := stack.NewPacketBuffer(stack.PacketBufferOptions{
			Payload: buffer.MakeWithData(buf[:n]),
		})
		e.mu.RLock()
		d := e.disp
		e.mu.RUnlock()
		if d != nil {
			d.DeliverNetworkPacket(proto, pkt)
		}
		pkt.DecRef()
	}
}

func main() {
	if len(os.Args) < 3 {
		log.Fatal("usage: hydra-bridge <abstract-socket> <socks5-addr> [stats-file]")
	}
	sockName := os.Args[1]
	socksAddr = os.Args[2]
	if len(os.Args) >= 4 {
		statsFilePath = os.Args[3]
	}

	fd, err := recvFd(sockName)
	if err != nil {
		log.Fatalf("recvFd: %v", err)
	}
	tunFd = fd
	log.Printf("[hb] got TUN fd=%d, socks5=%s", fd, socksAddr)
	log.Printf("[hb] NETSTACK v4 (gVisor TCP/IP) running")
	initStats()

	netStack = stack.New(stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol},
	})

	ep := &tunEndpoint{fd: tunFd, mtu: 1500}
	if err := netStack.CreateNIC(1, ep); err != nil {
		log.Fatalf("CreateNIC: %v", err)
	}
	netStack.SetRouteTable([]tcpip.Route{{
		Destination: tcpip.AddressWithPrefix{}.Subnet(),
		NIC:         1,
	}})

	// TCP: промышленный forwarder
	tf := tcp.NewForwarder(netStack, 0, 512, func(r *tcp.ForwarderRequest) {
		id := r.ID()
		var queue waiter.Queue
		ep, err := r.CreateEndpoint(&queue)
		if err != nil {
			log.Printf("[hb] tcp ep: %v", err)
			return
		}
		conn := gonet.NewTCPConn(&queue, ep)
		log.Printf("[hb] TCP %s:%d -> %s:%d", id.RemoteAddress, id.RemotePort, id.LocalAddress, id.LocalPort)
		go tcpSplice(conn, id)
	})
	netStack.SetTransportProtocolHandler(tcp.ProtocolNumber, tf.HandlePacket)

	// UDP: DNS через SOCKS5-TCP
	uf := udp.NewForwarder(netStack, func(r *udp.ForwarderRequest) {
		id := r.ID()
		if id.LocalPort != 53 {
			return
		}
		var queue waiter.Queue
		ep, err := r.CreateEndpoint(&queue)
		if err != nil {
			return
		}
		conn := gonet.NewUDPConn(netStack, &queue, ep)
		go dnsHandle(conn, id)
	})
	netStack.SetTransportProtocolHandler(udp.ProtocolNumber, uf.HandlePacket)

	go ep.pump()
	select {}
}

func tcpSplice(conn *gonet.TCPConn, id stack.TransportEndpointID) {
	defer conn.Close()
	d, err := proxy.SOCKS5("tcp", socksAddr, nil, proxy.Direct)
	if err != nil {
		return
	}
	dst := net.JoinHostPort(net.IP(id.LocalAddress.AsSlice()).String(), fmt.Sprint(id.LocalPort))
	up, err := d.Dial("tcp", dst)
	if err != nil {
		log.Printf("[hb] socks dial %s: %v", dst, err)
		return
	}
	defer up.Close()
	log.Printf("[hb] connected %s via SOCKS5 (netstack)", dst)

	go func() {
		io.Copy(up, conn)
		up.Close()
	}()
	io.Copy(conn, up)
}

func dnsHandle(conn *gonet.UDPConn, id stack.TransportEndpointID) {
	defer conn.Close()
	buf := make([]byte, 2048)
	for {
		n, from, err := conn.ReadFrom(buf)
		if err != nil {
			return
		}
		resp := dnsOverTCP(buf[:n], id)
		if resp == nil {
			continue
		}
		conn.WriteTo(resp, from)
	}
}

func dnsOverTCP(query []byte, id stack.TransportEndpointID) []byte {
	d, err := proxy.SOCKS5("tcp", socksAddr, nil, proxy.Direct)
	if err != nil {
		return nil
	}
	dst := net.JoinHostPort(net.IP(id.LocalAddress.AsSlice()).String(), "53")
	c, err := d.Dial("tcp", dst)
	if err != nil {
		return nil
	}
	defer c.Close()

	req := make([]byte, 2+len(query))
	binary.BigEndian.PutUint16(req[:2], uint16(len(query)))
	copy(req[2:], query)
	c.Write(req)

	resp := make([]byte, 4096)
	c.SetReadDeadline(time.Now().Add(3 * time.Second))
	n, err := c.Read(resp)
	if err != nil || n < 2 {
		return nil
	}
	return resp[2:n]
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
