package tunnel

import (
	"log"
)

var (
	bridge       *SocksBridge
	aivpnProv    *AIVPNProvider
	wdttProv     *WDTTProvider
)

// StartBridge запускает SOCKS5 мост (вызывается из Kotlin)
func StartBridge(tunFd int, mtu int, socksAddr string) error {
	if bridge != nil {
		return nil // уже запущен
	}

	bridge = NewSocksBridge(tunFd, uint32(mtu), socksAddr)
	if err := bridge.Start(); err != nil {
		bridge = nil
		return err
	}

	log.Printf("[Binding] bridge started, fd=%d, socks=%s", tunFd, socksAddr)
	return nil
}

// StopBridge останавливает SOCKS5 мост (вызывается из Kotlin)
func StopBridge() {
	if bridge != nil {
		bridge.Stop()
		bridge = nil
		log.Printf("[Binding] bridge stopped")
	}
}

// StartAIVPN запускает AIVPN провайдер (вызывается из Kotlin)
func StartAIVPN(binaryPath, connKey string, socksPort int) error {
	if aivpnProv != nil {
		return nil
	}

	aivpnProv = NewAIVPNProvider(binaryPath, connKey, socksPort)
	if err := aivpnProv.Start(); err != nil {
		aivpnProv = nil
		return err
	}

	log.Printf("[Binding] AIVPN started, port=%d", socksPort)
	return nil
}

// StopAIVPN останавливает AIVPN провайдер
func StopAIVPN() {
	if aivpnProv != nil {
		aivpnProv.Stop()
		aivpnProv = nil
		log.Printf("[Binding] AIVPN stopped")
	}
}

// StartWDTT запускает WDTT провайдер
func StartWDTT(binaryPath, vkHashes, connPassword string, socksPort int) error {
	if wdttProv != nil {
		return nil
	}

	wdttProv = NewWDTTProvider(binaryPath, vkHashes, connPassword, socksPort)
	if err := wdttProv.Start(); err != nil {
		wdttProv = nil
		return err
	}

	log.Printf("[Binding] WDTT started, port=%d", socksPort)
	return nil
}

// StopWDTT останавливает WDTT провайдер
func StopWDTT() {
	if wdttProv != nil {
		wdttProv.Stop()
		wdttProv = nil
		log.Printf("[Binding] WDTT stopped")
	}
}
