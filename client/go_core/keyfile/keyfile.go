// Key File v2 parser for gomobile (MANIFEST §5, docs/KEYFILE_V2.md)
package keyfile

import (
	"bufio"
	"errors"
	"fmt"
	"os"
	"strings"
)

// Touch initializes the Go runtime (required for gomobile before first call)
func InitRuntime() {}

// KeyFile represents a parsed Key File v2
type KeyFile struct {
	Version    int
	KeyId      string
	ExpiresAt  string
	MaxDevices int
	ClientName string
	Peers      []*Peer
}

// Peer represents a [Peer.<protocol>.<server_id>] section
type Peer struct {
	Protocol string
	ServerId string
	Endpoint string
	Label    string

	// AWG-specific fields (added for Этап 3b-i)
	PublicKey           string
	PrivateKey          string
	Address             string
	DNS                 string
	Jc                  string
	Jmin                string
	Jmax                string
	S1                  string
	S2                  string
	S3                  string
	S4                  string
	HeaderProtectionKey string
}

// Parse reads and parses a Key File v2 from disk
func Parse(filename string) (*KeyFile, error) {
	file, err := os.Open(filename)
	if err != nil {
		return nil, fmt.Errorf("open file: %w", err)
	}
	defer file.Close()

	kf := &KeyFile{}
	scanner := bufio.NewScanner(file)

	var currentSection string
	var currentPeer *Peer

	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())

		// Skip comments and empty lines
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}

		// Section header: [Section] or [Section.subsection]
		if strings.HasPrefix(line, "[") && strings.HasSuffix(line, "]") {
			section := line[1 : len(line)-1]
			parts := strings.SplitN(section, ".", 3)

			if len(parts) == 1 {
				currentSection = strings.ToLower(parts[0])
				currentPeer = nil
			} else if len(parts) == 3 && strings.ToLower(parts[0]) == "peer" {
				currentSection = "peer"
				currentPeer = &Peer{
					Protocol: parts[1],
					ServerId: parts[2],
				}
				kf.Peers = append(kf.Peers, currentPeer)
			}
			continue
		}

		// Key = Value
		eqIdx := strings.Index(line, "=")
		if eqIdx < 0 {
			continue
		}

		key := strings.TrimSpace(line[:eqIdx])
		value := strings.TrimSpace(line[eqIdx+1:])
		// Remove surrounding quotes if present
		value = strings.Trim(value, `"'`)

		switch currentSection {
		case "hydra":
			switch key {
			case "Version":
				fmt.Sscanf(value, "%d", &kf.Version)
			case "KeyId":
				kf.KeyId = value
			case "ExpiresAt":
				kf.ExpiresAt = value
			case "MaxDevices":
				fmt.Sscanf(value, "%d", &kf.MaxDevices)
			case "ClientName":
				kf.ClientName = value
			}

		case "peer":
			if currentPeer == nil {
				continue
			}
			switch key {
			case "Endpoint":
				currentPeer.Endpoint = value
			case "Label":
				currentPeer.Label = value
			// AWG-specific fields
			case "PublicKey":
				currentPeer.PublicKey = value
			case "PrivateKey":
				currentPeer.PrivateKey = value
			case "Address":
				currentPeer.Address = value
			case "DNS":
				currentPeer.DNS = value
			case "Jc":
				currentPeer.Jc = value
			case "Jmin":
				currentPeer.Jmin = value
			case "Jmax":
				currentPeer.Jmax = value
			case "S1":
				currentPeer.S1 = value
			case "S2":
				currentPeer.S2 = value
			case "S3":
				currentPeer.S3 = value
			case "S4":
				currentPeer.S4 = value
			case "HeaderProtectionKey":
				currentPeer.HeaderProtectionKey = value
			}
		}
	}

	if err := scanner.Err(); err != nil {
		return nil, fmt.Errorf("scan file: %w", err)
	}

	// Validation (К-05: минимум [Hydra] + >=1 Peer)
	if kf.Version == 0 {
		return nil, errors.New("missing or invalid [Hydra] section")
	}
	if len(kf.Peers) == 0 {
		return nil, errors.New("no [Peer.*] sections found")
	}

	return kf, nil
}

// PeerCount returns the number of peers (for gomobile top-level export)
func PeerCount(kf *KeyFile) int64 {
	return int64(len(kf.Peers))
}

// PeerAt returns the i-th peer (for gomobile top-level export)
func PeerAt(kf *KeyFile, i int64) *Peer {
	if i < 0 || i >= int64(len(kf.Peers)) {
		return nil
	}
	return kf.Peers[i]
}

// ExpiresAtString returns the expires_at field (for gomobile top-level export)
func ExpiresAtString(kf *KeyFile) string {
	return kf.ExpiresAt
}

// PeerCount возвращает количество peers в keyfile
func PeerCount(kf *KeyFile) int {
	if kf == nil {
		return 0
	}
	return len(kf.Peers)
}

// AllPeers возвращает все peers как JSON (для Kotlin)
func AllPeers(kf *KeyFile) string {
	if kf == nil || len(kf.Peers) == 0 {
		return "[]"
	}

	type peerJSON struct {
		Protocol            string `json:"protocol"`
		ServerID            string `json:"server_id"`
		Endpoint            string `json:"endpoint"`
		Label               string `json:"label"`
		PublicKey           string `json:"public_key,omitempty"`
		PrivateKey          string `json:"private_key,omitempty"`
		Address             string `json:"address,omitempty"`
		DNS                 string `json:"dns,omitempty"`
		Jc                  int    `json:"jc,omitempty"`
		Jmin                int    `json:"jmin,omitempty"`
		Jmax                int    `json:"jmax,omitempty"`
		S1                  int    `json:"s1,omitempty"`
		S2                  int    `json:"s2,omitempty"`
		S3                  int    `json:"s3,omitempty"`
		S4                  int    `json:"s4,omitempty"`
		HeaderProtectionKey string `json:"header_protection_key,omitempty"`
		Key                 string `json:"key,omitempty"`      // AIVPN URL
		Password            string `json:"password,omitempty"` // WDTT
	}

	peers := make([]peerJSON, len(kf.Peers))
	for i, p := range kf.Peers {
		peers[i] = peerJSON{
			Protocol:            p.Protocol,
			ServerID:            p.ServerID,
			Endpoint:            p.Endpoint,
			Label:               p.Label,
			PublicKey:           p.PublicKey,
			PrivateKey:          p.PrivateKey,
			Address:             p.Address,
			DNS:                 p.DNS,
			Jc:                  p.Jc,
			Jmin:                p.Jmin,
			Jmax:                p.Jmax,
			S1:                  p.S1,
			S2:                  p.S2,
			S3:                  p.S3,
			S4:                  p.S4,
			HeaderProtectionKey: p.HeaderProtectionKey,
			Key:                 p.Key,
			Password:            p.Password,
		}
	}

	data, _ := json.Marshal(peers)
	return string(data)
}
