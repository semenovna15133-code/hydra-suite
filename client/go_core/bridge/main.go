package main

import (
	"encoding/binary"
	"fmt"
	"log"
	"net"
	"os"
	"sync"
	"time"

	"golang.org/x/net/proxy"
	"golang.org/x/sys/unix"
)

var socksAddr string
var statsFilePath string
var tunFd int

// Статистика
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
			rx := rxBytes
			tx := txBytes
			ms := int64(time.Since(sessionStart).Milliseconds())
			statsMu.Unlock()
						data := fmt.Sprintf("{\"rx_bytes\":%d,\"tx_bytes\":%d,\"session_ms\":%d}", rx, tx, ms)
			os.WriteFile(statsFilePath, []byte(data), 0666)
			log.Printf("[hb] stats rx_bytes=%d tx_bytes=%d session_ms=%d", rx, tx, ms)
		}
	}()
}

func addRx(n int) {
	statsMu.Lock()
	rxBytes += int64(n)
	statsMu.Unlock()
}

func addTx(n int) {
	statsMu.Lock()
	txBytes += int64(n)
	statsMu.Unlock()
}

// TCP соединения
type tcpConn struct {
	mu       sync.Mutex
	socks    net.Conn
	srcIP    net.IP
	srcPort  uint16
	dstIP    net.IP
	dstPort  uint16
	ourSeq   uint32
	theirSeq uint32
}

var conns = make(map[string]*tcpConn)
var connsMu sync.Mutex

func main() {
	if len(os.Args) < 3 {
		log.Fatal("usage: hydra-bridge <abstract-socket> <socks5-addr>")
	}
	sockName := os.Args[1]
	socksAddr = os.Args[2]
	statsFilePath = "/data/user/0/dev.hydra.hydra_client/files/hb_stats.json"
	if len(os.Args) >= 4 {
		statsFilePath = os.Args[3]
	}

	fd, err := recvFd(sockName)
	if err != nil {
		log.Fatalf("recvFd: %v", err)
	}
	tunFd = fd
	log.Printf("[hb] got TUN fd=%d, socks5=%s", fd, socksAddr)
	log.Printf("[hb] tun2socks FULL-DUPLEX running")
	initStats()

	run()
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

func run() {
	buf := make([]byte, 4096)
	pfds := []unix.PollFd{{Fd: int32(tunFd), Events: unix.POLLIN}}

	for {
		if _, err := unix.Poll(pfds, 1000); err != nil {
			continue
		}
		n, err := unix.Read(tunFd, buf)
		if err != nil || n < 20 {
			continue
		}
		addRx(n)

		if buf[0]>>4 != 4 {
			continue
		}
		ihl := int(buf[0]&0x0f) * 4
		if n < ihl {
			continue
		}

		srcIP := net.IP(append([]byte{}, buf[12:16]...))
		dstIP := net.IP(append([]byte{}, buf[16:20]...))
		proto := buf[9]

		switch proto {
		case 6:
			pkt := append([]byte{}, buf[:n]...)
			go handleTCP(pkt, ihl, srcIP, dstIP)
		case 17:
			if n >= ihl+8 {
				dstPort := binary.BigEndian.Uint16(buf[ihl+2 : ihl+4])
				if dstPort == 53 {
					pkt := append([]byte{}, buf[:n]...)
					go handleDNS(pkt, ihl, srcIP, dstIP)
				}
			}
		}
	}
}

func handleTCP(pkt []byte, ihl int, srcIP, dstIP net.IP) {
	if len(pkt) < ihl+20 {
		return
	}
	srcPort := binary.BigEndian.Uint16(pkt[ihl : ihl+2])
	dstPort := binary.BigEndian.Uint16(pkt[ihl+2 : ihl+4])
	seq := binary.BigEndian.Uint32(pkt[ihl+4 : ihl+8])
	flags := pkt[ihl+13]
	tcpHdrLen := int(pkt[ihl+12]>>4) * 4

	key := fmt.Sprintf("%s:%d-%s:%d", srcIP, srcPort, dstIP, dstPort)

	// SYN: новое соединение
	if flags&0x02 != 0 && flags&0x10 == 0 {
		log.Printf("[hb] TCP SYN %s:%d -> %s:%d", srcIP, srcPort, dstIP, dstPort)
		go newTCPConn(srcIP, srcPort, dstIP, dstPort, seq)
		return
	}

	connsMu.Lock()
	conn, exists := conns[key]
	connsMu.Unlock()
	if !exists {
		return
	}

	conn.mu.Lock()
	defer conn.mu.Unlock()

	// ACK с данными: forward в SOCKS5
	if flags&0x10 != 0 && len(pkt) > ihl+tcpHdrLen {
		payload := pkt[ihl+tcpHdrLen:]
		conn.socks.Write(payload)
		conn.theirSeq = seq + uint32(len(payload))
		sendTCP(conn, 0x10, nil)
	}

	// FIN/RST: закрыть
	if flags&0x01 != 0 || flags&0x04 != 0 {
		conn.socks.Close()
		connsMu.Lock()
		delete(conns, key)
		connsMu.Unlock()
	}
}

func newTCPConn(srcIP net.IP, srcPort uint16, dstIP net.IP, dstPort uint16, theirSeq uint32) {
	d, err := proxy.SOCKS5("tcp", socksAddr, nil, proxy.Direct)
	if err != nil {
		log.Printf("[hb] socks error: %v", err)
		return
	}
	dst := net.JoinHostPort(dstIP.String(), fmt.Sprint(dstPort))
	socks, err := d.Dial("tcp", dst)
	if err != nil {
		log.Printf("[hb] socks dial %s: %v", dst, err)
		return
	}
	log.Printf("[hb] connected %s via SOCKS5", dst)

	conn := &tcpConn{
		socks:    socks,
		srcIP:    srcIP,
		srcPort:  srcPort,
		dstIP:    dstIP,
		dstPort:  dstPort,
		ourSeq:   1000,
		theirSeq: theirSeq + 1,
	}

	key := fmt.Sprintf("%s:%d-%s:%d", srcIP, srcPort, dstIP, dstPort)
	connsMu.Lock()
	conns[key] = conn
	connsMu.Unlock()

	// SYN-ACK
	sendTCP(conn, 0x12, nil)
	conn.mu.Lock()
	conn.ourSeq++
	conn.mu.Unlock()

	// Читаем из SOCKS5, пишем в TUN
	go func() {
		buf := make([]byte, 4096)
		for {
			log.Printf("[hb] socks.Read: reading...")
			n, err := socks.Read(buf)
			log.Printf("[hb] socks.Read: n=%d err=%v", n, err)
			if err != nil {
				socks.Close()
				connsMu.Lock()
				delete(conns, key)
				connsMu.Unlock()
				return
			}
			data := append([]byte{}, buf[:n]...)
			conn.mu.Lock()
			sendTCP(conn, 0x18, data)
			conn.ourSeq += uint32(n)
			conn.mu.Unlock()
		}
	}()
}

func sendTCP(conn *tcpConn, flags byte, payload []byte) {
	log.Printf("[hb] sendTCP flags=0x%x payload=%d", flags, len(payload))
	ipLen := 20 + 20 + len(payload)
	pkt := make([]byte, ipLen)

	pkt[0] = 0x45
	binary.BigEndian.PutUint16(pkt[2:4], uint16(ipLen))
	pkt[8] = 64
	pkt[9] = 6
	copy(pkt[12:16], conn.dstIP.To4())
	copy(pkt[16:20], conn.srcIP.To4())
	binary.BigEndian.PutUint16(pkt[10:12], ipChecksum(pkt[:20]))

	binary.BigEndian.PutUint16(pkt[20:22], conn.dstPort)
	binary.BigEndian.PutUint16(pkt[22:24], conn.srcPort)
	binary.BigEndian.PutUint32(pkt[24:28], conn.ourSeq)
	binary.BigEndian.PutUint32(pkt[28:32], conn.theirSeq)
	pkt[32] = 0x50
	pkt[33] = flags
	binary.BigEndian.PutUint16(pkt[34:36], 65535)
	copy(pkt[40:], payload)
	binary.BigEndian.PutUint16(pkt[36:38], tcpChecksum(pkt[12:20], pkt[20:]))

	addTx(len(pkt))
	unix.Write(tunFd, pkt)
}

func handleDNS(pkt []byte, ihl int, srcIP, dstIP net.IP) {
	srcPort := binary.BigEndian.Uint16(pkt[ihl : ihl+2])
	dstPort := binary.BigEndian.Uint16(pkt[ihl+2 : ihl+4])
	payload := pkt[ihl+8:]

	d, err := proxy.SOCKS5("tcp", socksAddr, nil, proxy.Direct)
	if err != nil {
		return
	}
	conn, err := d.Dial("tcp", net.JoinHostPort(dstIP.String(), "53"))
	if err != nil {
		return
	}
	defer conn.Close()

	req := make([]byte, 2+len(payload))
	binary.BigEndian.PutUint16(req[:2], uint16(len(payload)))
	copy(req[2:], payload)
	conn.Write(req)

	resp := make([]byte, 4096)
	conn.SetReadDeadline(time.Now().Add(3 * time.Second))
	n, err := conn.Read(resp)
	if err != nil || n < 2 {
		return
	}
	resp = resp[2:n]
	log.Printf("[hb] DNS resolved via SOCKS5 (%d bytes)", len(resp))

	sendUDP(srcIP, srcPort, dstIP, dstPort, resp)
}

func sendUDP(srcIP net.IP, srcPort uint16, dstIP net.IP, dstPort uint16, payload []byte) {
	ipLen := 20 + 8 + len(payload)
	pkt := make([]byte, ipLen)

	pkt[0] = 0x45
	binary.BigEndian.PutUint16(pkt[2:4], uint16(ipLen))
	pkt[8] = 64
	pkt[9] = 17
	copy(pkt[12:16], dstIP.To4())
	copy(pkt[16:20], srcIP.To4())
	binary.BigEndian.PutUint16(pkt[10:12], ipChecksum(pkt[:20]))

	binary.BigEndian.PutUint16(pkt[20:22], dstPort)
	binary.BigEndian.PutUint16(pkt[22:24], srcPort)
	binary.BigEndian.PutUint16(pkt[24:26], uint16(8+len(payload)))
	pkt[26] = 0
	pkt[27] = 0
	copy(pkt[28:], payload)

	addTx(len(pkt))
	unix.Write(tunFd, pkt)
}

func ipChecksum(hdr []byte) uint16 {
	var sum uint32
	for i := 0; i < len(hdr); i += 2 {
		sum += uint32(binary.BigEndian.Uint16(hdr[i : i+2]))
	}
	sum = (sum >> 16) + (sum & 0xffff)
	sum += sum >> 16
	return ^uint16(sum)
}

func tcpChecksum(pseudoIP, tcpSeg []byte) uint16 {
	var sum uint32
	sum += uint32(binary.BigEndian.Uint16(pseudoIP[0:2]))
	sum += uint32(binary.BigEndian.Uint16(pseudoIP[2:4]))
	sum += uint32(binary.BigEndian.Uint16(pseudoIP[4:6]))
	sum += uint32(binary.BigEndian.Uint16(pseudoIP[6:8]))
	sum += uint32(6)
	sum += uint32(len(tcpSeg))
	for i := 0; i < len(tcpSeg); i += 2 {
		if i+1 < len(tcpSeg) {
			sum += uint32(binary.BigEndian.Uint16(tcpSeg[i : i+2]))
		} else {
			sum += uint32(tcpSeg[i]) << 8
		}
	}
	sum = (sum >> 16) + (sum & 0xffff)
	sum += sum >> 16
	return ^uint16(sum)
}
