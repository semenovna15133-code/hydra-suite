package tunnel

import (
	"context"
	"fmt"
	"log"
	"os"
	"os/exec"
	"sync"
)

// WDTTProvider запускает бинарник wdtt-client в SOCKS5 режиме
type WDTTProvider struct {
	binaryPath   string
	vkHashes     string
	connPassword string
	socksPort    int
	cmd          *exec.Cmd
	ctx          context.Context
	cancel       context.CancelFunc
	mu           sync.Mutex
	running      bool
}

// NewWDTTProvider создаёт провайдер WDTT
func NewWDTTProvider(binaryPath, vkHashes, connPassword string, socksPort int) *WDTTProvider {
	ctx, cancel := context.WithCancel(context.Background())
	return &WDTTProvider{
		binaryPath:   binaryPath,
		vkHashes:     vkHashes,
		connPassword: connPassword,
		socksPort:    socksPort,
		ctx:          ctx,
		cancel:       cancel,
	}
}

// Start запускает бинарник wdtt-client
func (p *WDTTProvider) Start() error {
	p.mu.Lock()
	defer p.mu.Unlock()

	if p.running {
		return fmt.Errorf("already running")
	}

	if _, err := os.Stat(p.binaryPath); err != nil {
		return fmt.Errorf("binary not found: %v", err)
	}

	os.Chmod(p.binaryPath, 0755)

	socksAddr := fmt.Sprintf("127.0.0.1:%d", p.socksPort)
	p.cmd = exec.CommandContext(p.ctx, p.binaryPath,
		"--mode", "socks5",
		"--socks-listen", socksAddr,
		"--vk-hashes", p.vkHashes,
		"--conn-password", p.connPassword,
	)

	p.cmd.Stdout = os.Stdout
	p.cmd.Stderr = os.Stderr

	if err := p.cmd.Start(); err != nil {
		return fmt.Errorf("failed to start wdtt-client: %v", err)
	}

	p.running = true
	log.Printf("[WDTTProvider] started pid=%d, socks=%s", p.cmd.Process.Pid, socksAddr)

	go func() {
		err := p.cmd.Wait()
		p.mu.Lock()
		p.running = false
		p.mu.Unlock()
		if err != nil && p.ctx.Err() == nil {
			log.Printf("[WDTTProvider] exited with error: %v", err)
		}
	}()

	return nil
}

// Stop останавливает бинарник
func (p *WDTTProvider) Stop() error {
	p.mu.Lock()
	defer p.mu.Unlock()

	if !p.running {
		return nil
	}

	p.cancel()
	if p.cmd != nil && p.cmd.Process != nil {
		p.cmd.Process.Kill()
	}

	log.Printf("[WDTTProvider] stopped")
	return nil
}

// SocksAddr возвращает адрес SOCKS5 proxy
func (p *WDTTProvider) SocksAddr() string {
	return fmt.Sprintf("127.0.0.1:%d", p.socksPort)
}

// IsRunning возвращает статус работы
func (p *WDTTProvider) IsRunning() bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.running
}
