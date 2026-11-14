package main

import (
	"encoding/json"
	"errors"
	"io/fs"
	"os"
	"path/filepath"
	"sync"
)

// Config is persisted as <data>/config.json so the server can be started by
// double-clicking the binary. Command-line flags override it for one run.
type Config struct {
	// Listen is the public call port: TLS signaling + TURN (TCP and UDP).
	Listen string `json:"listen"`
	// ConsoleListen is the web console (plain HTTP). Keep it on loopback or
	// restrict it with the host firewall / a reverse proxy.
	ConsoleListen string `json:"console_listen"`
	// PasswordHash is the argon2id hash of the Android access password,
	// managed from the web console. Empty = no password.
	PasswordHash string   `json:"password_hash"`
	CertFile     string   `json:"cert_file"`
	KeyFile      string   `json:"key_file"`
	Hosts        []string `json:"hosts"`
	DisableUDP   bool     `json:"disable_udp"`
	OpenBrowser  *bool    `json:"open_browser"`
}

const (
	defaultListen        = ":8443"
	defaultConsoleListen = "127.0.0.1:8080"
)

type configStore struct {
	path string
	mu   sync.Mutex
	cfg  Config
}

func loadOrCreateConfig(path string) (*configStore, bool, error) {
	s := &configStore{path: path, cfg: Config{Listen: defaultListen, ConsoleListen: defaultConsoleListen}}
	data, err := os.ReadFile(path)
	if errors.Is(err, fs.ErrNotExist) {
		t := true
		s.cfg.OpenBrowser = &t
		return s, true, s.saveLocked()
	}
	if err != nil {
		return nil, false, err
	}
	if err := json.Unmarshal(data, &s.cfg); err != nil {
		return nil, false, err
	}
	if s.cfg.Listen == "" {
		s.cfg.Listen = defaultListen
	}
	if s.cfg.ConsoleListen == "" {
		s.cfg.ConsoleListen = defaultConsoleListen
	}
	return s, false, nil
}

// setPasswordHash persists only the hash; other fields are kept as they are
// on disk (runtime flag overrides are never written back).
func (s *configStore) setPasswordHash(h string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.cfg.PasswordHash = h
	return s.saveLocked()
}

func (s *configStore) saveLocked() error {
	out, err := json.MarshalIndent(&s.cfg, "", "  ")
	if err != nil {
		return err
	}
	tmp, err := os.CreateTemp(filepath.Dir(s.path), ".config-*.json")
	if err != nil {
		return err
	}
	defer os.Remove(tmp.Name())
	if err := tmp.Chmod(0o600); err != nil {
		tmp.Close()
		return err
	}
	if _, err := tmp.Write(append(out, '\n')); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	return os.Rename(tmp.Name(), s.path)
}
