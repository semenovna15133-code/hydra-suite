package main

import (
	"encoding/binary"
	"fmt"
	"log"
	"net"
	"os"
	"time"

	"golang.org/x/net/proxy"
	"golang.org/x/sys/unix"
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
	log.Printf("[hb] got TUN fd=%d, socks5=%s", fd, socksAddr)
	log.Printf("[hb] tun2socks running (pure Go, no gVisor)")

	run(fd)
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

func run(tunFd int) {
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

		// Парсим IPv4 header (20 байт минимум)
		if buf[0]>>4 != 4 {
			continue // не IPv4
		}
		ihl := int(buf[0]&0x0f) * 4
		if n < ihl {
			continue
		}

		srcIP := net.IP(buf[12:16])
		dstIP := net.IP(buf[16:20])
		proto := buf[9]

		switch proto {
		case 6: // TCP
			if n < ihl+8 {
				continue
			}
			srcPort := binary.BigEndian.Uint16(buf[ihl : ihl+2])
			dstPort := binary.BigEndian.Uint16(buf[ihl+2 : ihl+4])
			log.Printf("[hb] TCP %s:%d -> %s:%d", srcIP, srcPort, dstIP, dstPort)
			go handleTCP(tunFd, buf[:n], srcIP, srcPort, dstIP, dstPort)

		case 17: // UDP
			if n < ihl+8 {
				continue
			}
			srcPort := binary.BigEndian.Uint16(buf[ihl : ihl+2])
			dstPort := binary.BigEndian.Uint16(buf[ihl+2 : ihl+4])
			if dstPort == 53 {
				log.Printf("[hb] UDP DNS %s:%d -> %s:53", srcIP, srcPort, dstIP)
				go handleDNS(tunFd, buf[:n], srcIP, srcPort, dstIP)
			}
		}
	}
}

func handleTCP(tunFd int, pkt []byte, srcIP net.IP, srcPort uint16, dstIP net.IP, dstPort uint16) {
	// Dial SOCKS5
	d, err := proxy.SOCKS5("tcp", socksAddr, nil, proxy.Direct)
	if err != nil {
		log.Printf("[hb] socks proxy error: %v", err)
		return
	}
	dst := net.JoinHostPort(dstIP.String(), fmt.Sprint(dstPort))
	conn, err := d.Dial("tcp", dst)
	if err != nil {
		log.Printf("[hb] socks dial %s: %v", dst, err)
		return
	}
	defer conn.Close()

	// Простой forward: читаем из SOCKS5, логируем (response обратно в TUN не пишем для простоты)
	// TODO: полный bidirectional splice требует IP packet crafting
	log.Printf("[hb] connected to %s via SOCKS5", dst)

	// Читаем ответ (для теста)
	buf := make([]byte, 4096)
	conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	n, err := conn.Read(buf)
	if err != nil {
		log.Printf("[hb] read from %s: %v", dst, err)
		return
	}
	log.Printf("[hb] received %d bytes from %s", n, dst)
	// TODO: craft IP response packet and write to tunFd
}

func handleDNS(tunFd int, pkt []byte, srcIP net.IP, srcPort uint16, dstIP net.IP) {
	// DNS через SOCKS5 UDP ASSOCIATE (или простой TCP relay)
	// Для простоты: логируем
	log.Printf("[hb] DNS query to %s (not forwarded yet)", dstIP)
	// TODO: forward DNS query via SOCKS5 UDP
}
