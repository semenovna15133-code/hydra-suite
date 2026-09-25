package keyfile

import (
	"bufio"
	"fmt"
	"os"
	"strings"
	"time"
)

// KeyFile представляет самодостаточный файл ключа (К-05: минимум [Hydra] + >=1 Peer)
type KeyFile struct {
	Version    int
	KeyId      string
	ExpiresAt  time.Time
	MaxDevices int
	ClientName string
	Peers      []Peer
}

// Peer описывает один сервер/протокол из ключа
type Peer struct {
	Protocol string // awg, wdtt, aivpn
	ServerId string
	Endpoint string
	Label    string
	// protocol-specific поля добавятся на Этапе 3b
}

// Parse читает и парсит файл ключа
func Parse(path string) (*KeyFile, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, fmt.Errorf("открыть файл: %w", err)
	}
	defer f.Close()

	kf := &KeyFile{Peers: []Peer{}}
	scanner := bufio.NewScanner(f)
	var currentPeer *Peer

	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}

		if strings.HasPrefix(line, "[Hydra]") {
			continue
		}

		if strings.HasPrefix(line, "[Peer.") && strings.HasSuffix(line, "]") {
			// Формат: [Peer.<protocol>.<server_id>]
			inner := strings.TrimSuffix(strings.TrimPrefix(line, "[Peer."), "]")
			parts := strings.SplitN(inner, ".", 2)
			if len(parts) != 2 {
				return nil, fmt.Errorf("неверный формат секции: %s", line)
			}
			if currentPeer != nil {
				kf.Peers = append(kf.Peers, *currentPeer)
			}
			currentPeer = &Peer{Protocol: parts[0], ServerId: parts[1]}
			continue
		}

		// Парсим key=value
		if idx := strings.Index(line, "="); idx > 0 {
			key := strings.TrimSpace(line[:idx])
			value := strings.TrimSpace(line[idx+1:])

			if currentPeer == nil {
				// Секция [Hydra]
				switch key {
				case "Version":
					fmt.Sscanf(value, "%d", &kf.Version)
				case "KeyId":
					kf.KeyId = value
				case "ExpiresAt":
					if t, err := time.Parse("2006-01-02 15:04:05", value); err == nil {
						kf.ExpiresAt = t
					}
				case "MaxDevices":
					fmt.Sscanf(value, "%d", &kf.MaxDevices)
				case "ClientName":
					kf.ClientName = value
				}
			} else {
				// Секция [Peer.*]
				switch key {
				case "Endpoint":
					currentPeer.Endpoint = value
				case "Label":
					currentPeer.Label = strings.Trim(value, `"`)
				}
			}
		}
	}

	if currentPeer != nil {
		kf.Peers = append(kf.Peers, *currentPeer)
	}

	if len(kf.Peers) == 0 {
		return nil, fmt.Errorf("минимальный ключ требует хотя бы один [Peer.*]")
	}

	return kf, nil
}
