package tunnel

import (
	"context"
	"fmt"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"sync"
)

// AIVPNProvider запускает бинарник aivpn-client в SOCKS5 режиме
type AIVPNProvider struct {
	binaryPath  string
	connKey     string
	socksPort   int
	cmd         *exec.Cmd
	ctx         context.Context
	cancel      context.CancelFunc
	mu          sync.Mutex
	running     bool
}

// NewAIVPNProvider создаёт провайдер AIVPN
func NewAIVPNProvider(binaryPath, connKey string, socksPort int) *AIVPNProvider {
	ctx, cancel := context.WithCancel(context.Background())
	return &AIVPNProvider{
		binaryPath: binaryPath,
		connKey:    connKey,
		socksPort:  socksPort,
		ctx:        ctx,
		cancel:     cancel,
	}
}

// Start запускает бинарник aivpn-client
func (p *AIVPNProvider) Start() error {
	p.mu.Lock()
	defer p.mu.Unlock()

	if p.running {
		return fmt.Errorf("already running")
	}

	// Проверяем что бинарник существует
	if _, err := os.Stat(p.binaryPath); err != nil {
		return fmt.Errorf("binary not found: %v", err)
	}

	// Делаем бинарник исполняемым
	os.Chmod(p.binaryPath, 0755)

	// Запускаем с --connection-key и --proxy-listen
	socksAddr := fmt.Sprintf("127.0.0.1:%d", p.socksPort)
	p.cmd = exec.CommandContext(p.ctx, p.binaryPath,
		"--connection-key", p.connKey,
		"--proxy-listen", socksAddr,
	)

	// Перенаправляем логи
	p.cmd.Stdout = os.Stdout
	p.cmd.Stderr = os.Stderr

	if err := p.cmd.Start(); err != nil {
		return fmt.Errorf("failed to start aivpn-client: %v", err)
	}

	p.running = true
	log.Printf("[AIVPNProvider] started pid=%d, socks=%s", p.cmd.Process.Pid, socksAddr)

	// Ждём завершения в фоне
	go func() {
		err := p.cmd.Wait()
		p.mu.Lock()
		p.running = false
		p.mu.Unlock()
		if err != nil && p.ctx.Err() == nil {
			log.Printf("[AIVPNProvider] exited with error: %v", err)
		}
	}()

	return nil
}

// Stop останавливает бинарник
func (p *AIVPNProvider) Stop() error {
	p.mu.Lock()
	defer p.mu.Unlock()

	if !p.running {
		return nil
	}

	p.cancel()
	if p.cmd != nil && p.cmd.Process != nil {
		p.cmd.Process.Kill()
	}

	log.Printf("[AIVPNProvider] stopped")
	return nil
}

// SocksAddr возвращает адрес SOCKS5 proxy
func (p *AIVPNProvider) SocksAddr() string {
	return fmt.Sprintf("127.0.0.1:%d", p.socksPort)
}

// IsRunning возвращает статус работы
func (p *AIVPNProvider) IsRunning() bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.running
}
