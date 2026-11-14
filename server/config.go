package main

import (
	"encoding/json"
	"errors"
	"io/fs"
	"os"
)

// Config is persisted as <data>/config.json so the server can be started by
// double-clicking the binary. Command-line flags override it.
type Config struct {
	Listen      string   `json:"listen"`
	Password    string   `json:"password"`
	CertFile    string   `json:"cert_file"`
	KeyFile     string   `json:"key_file"`
	Hosts       []string `json:"hosts"`
	DisableUDP  bool     `json:"disable_udp"`
	OpenBrowser *bool    `json:"open_browser"`
}

func loadOrCreateConfig(path string) (*Config, bool, error) {
	cfg := &Config{Listen: ":8443"}
	data, err := os.ReadFile(path)
	if errors.Is(err, fs.ErrNotExist) {
		t := true
		cfg.OpenBrowser = &t
		out, _ := json.MarshalIndent(cfg, "", "  ")
		if err := os.WriteFile(path, out, 0o600); err != nil {
			return nil, false, err
		}
		return cfg, true, nil
	}
	if err != nil {
		return nil, false, err
	}
	if err := json.Unmarshal(data, cfg); err != nil {
		return nil, false, err
	}
	if cfg.Listen == "" {
		cfg.Listen = ":8443"
	}
	return cfg, false, nil
}
