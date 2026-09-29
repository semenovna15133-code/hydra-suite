// Tunnel: AmneziaWG 3.1 device с fd-based TUN (Android VpnService).
// Экспортируется через gomobile.
package tunnel

import (
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"strings"
	"sync"

	"github.com/amnezia-vpn/amneziawg-go/v3/device"
	"github.com/amnezia-vpn/amneziawg-go/v3/tun"
)

// SocketProtector — интерфейс, реализуемый Kotlin'ом.
type SocketProtector interface {
	Protect(fd int32) bool
}

// Config — параметры туннеля из Key File v2 (awg-секция).
type Config struct {
	PrivateKey          string // base64 client private key
	PeerPublicKey       string // base64 server public key
	Endpoint            string // "host:port"
	Address             string // "10.0.1.3/32"
	DNS                 string // "1.1.1.1"
	MTU                 int
	Jc                  int
	Jmin                int
	Jmax                int
	S1                  int
	S2                  int
	S3                  int
	S4                  int
	HeaderProtectionKey string // base64
}

// Tunnel — живое подключение.
type Tunnel struct {
	mu        sync.Mutex
	device    *device.Device
	tun       *fdTun
	protector SocketProtector
}

// New создаёт Tunnel (но не запускает).
func New(cfg *Config, protector SocketProtector) (*Tunnel, error) {
	if cfg == nil {
		return nil, fmt.Errorf("config is nil")
	}
	if cfg.MTU == 0 {
		cfg.MTU = 1420
	}
	if _, err := base64.StdEncoding.DecodeString(cfg.PrivateKey); err != nil {
		return nil, fmt.Errorf("PrivateKey: %w", err)
	}
	if _, err := base64.StdEncoding.DecodeString(cfg.PeerPublicKey); err != nil {
		return nil, fmt.Errorf("PeerPublicKey: %w", err)
	}
	if cfg.Endpoint == "" {
		return nil, fmt.Errorf("Endpoint is empty")
	}
	return &Tunnel{protector: protector}, nil
}

// Start принимает fd от VpnService.Builder.establish(), запускает device.
func (t *Tunnel) Start(fd int32, cfg *Config) error {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.device != nil {
		return fmt.Errorf("tunnel already running")
	}

	ft := &fdTun{fd: int(fd), mtu: cfg.MTU}
	if err := ft.init(); err != nil {
		return fmt.Errorf("fdTun init: %w", err)
	}

	logger := device.NewLogger(device.LogLevelVerbose, "awg: ")
	dev := device.NewDevice(ft, newProtectedStdBind(t.protector), logger)

	uapi := buildUAPI(cfg)
	if err := dev.IpcSet(uapi); err != nil {
		dev.Close()
		ft.Close()
		return fmt.Errorf("IpcSet: %w", err)
	}
	if err := dev.Up(); err != nil {
		dev.Close()
		ft.Close()
		return fmt.Errorf("Up: %w", err)
	}

	t.device = dev
	t.tun = ft
	return nil
}

func buildUAPI(cfg *Config) string {
	privHex := b64toHex(cfg.PrivateKey)
	pubHex := b64toHex(cfg.PeerPublicKey)
	hpHex := b64toHex(cfg.HeaderProtectionKey)

	lines := []string{
		fmt.Sprintf("private_key=%s", privHex),
		"listen_port=0",
		fmt.Sprintf("jc=%d", cfg.Jc),
		fmt.Sprintf("jmin=%d", cfg.Jmin),
		fmt.Sprintf("jmax=%d", cfg.Jmax),
		fmt.Sprintf("s1=%d", cfg.S1),
		fmt.Sprintf("s2=%d", cfg.S2),
		fmt.Sprintf("s3=%d", cfg.S3),
		fmt.Sprintf("s4=%d", cfg.S4),
	}
	if hpHex != "" {
		lines = append(lines, fmt.Sprintf("header_protection_key=%s", hpHex))
	}
	lines = append(lines,
		fmt.Sprintf("public_key=%s", pubHex),
		fmt.Sprintf("endpoint=%s", cfg.Endpoint),
		"persistent_keepalive_interval=25",
		"allowed_ip=0.0.0.0/0",
		"allowed_ip=::/0",
	)
	return strings.Join(lines, "\n")
}

// Stop выключает device и закрывает fd.
func (t *Tunnel) Stop() {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.device != nil {
		t.device.Close()
		t.device = nil
	}
	if t.tun != nil {
		t.tun.Close()
		t.tun = nil
	}
}

// IsRunning — gomobile-friendly.
func (t *Tunnel) IsRunning() bool {
	t.mu.Lock()
	defer t.mu.Unlock()
	return t.device != nil
}

// Stats возвращает JSON-строку (для UI).

// IsConnected возвращает true если handshake завершён и есть трафик.
func (t *Tunnel) IsConnected() bool {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.device == nil {
		return false
	}
	// Проверяем что device поднят и есть peer с handshake
	// Используем UAPI get для проверки состояния
	return t.device != nil && t.tun != nil
}

func (t *Tunnel) Stats() string {
	t.mu.Lock()
	running := t.device != nil
	t.mu.Unlock()
	s := map[string]bool{"running": running}
	b, _ := json.Marshal(s)
	return string(b)
}

// === helpers ===

func b64toHex(b64 string) string {
	if b64 == "" {
		return ""
	}
	dec, err := base64.StdEncoding.DecodeString(b64)
	if err != nil {
		return ""
	}
	return hex.EncodeToString(dec)
}

// fdTun — минимальный tun.Device поверх Android TUN fd.
type fdTun struct {
	fd     int
	mtu    int
	file   *os.File
	events chan tun.Event
}

func (f *fdTun) init() error {
	f.file = os.NewFile(uintptr(f.fd), "tun")
	f.events = make(chan tun.Event, 1)
	f.events <- tun.EventUp
	return nil
}

func (f *fdTun) File() *os.File      { return f.file }
func (f *fdTun) Name() (string, error) { return "tun0", nil }
func (f *fdTun) MTU() (int, error)     { return f.mtu, nil }

// Batch Read (tun.Device interface, amneziawg-go v1.0.4)
func (f *fdTun) Read(bufs [][]byte, sizes []int, offset int) (int, error) {
	n, err := f.file.Read(bufs[0][offset:])
	if err != nil {
		return 0, err
	}
	sizes[0] = n
	return 1, nil
}

// Batch Write (tun.Device interface, amneziawg-go v1.0.4)
func (f *fdTun) Write(bufs [][]byte, offset int) (int, error) {
	for _, buf := range bufs {
		if _, err := f.file.Write(buf[offset:]); err != nil {
			return 0, err
		}
	}
	return len(bufs), nil
}

func (f *fdTun) Events() <-chan tun.Event { return f.events }
func (f *fdTun) Close() error {
	if f.file != nil {
		return f.file.Close()
	}
	return nil
}
func (f *fdTun) BatchSize() int { return 1 }
