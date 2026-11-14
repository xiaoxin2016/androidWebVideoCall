package main

import (
	"embed"
	"encoding/json"
	"io/fs"
	"net/http"
)

//go:embed web
var webFS embed.FS

type serverInfo struct {
	Fingerprint string   `json:"fingerprint"`
	Version     string   `json:"version"`
	CallPort    int      `json:"callPort"`
	ConsolePort int      `json:"consolePort"`
	UDP         bool     `json:"udp"`
	LocalIPs    []string `json:"localIPs"`
}

// newConsoleHandler serves the web console. Access control for this port is
// left to the bind address / host firewall / reverse proxy by design.
func newConsoleHandler(hub *Hub, info serverInfo) http.Handler {
	static, _ := fs.Sub(webFS, "web")
	mux := http.NewServeMux()
	mux.Handle("GET /", http.FileServerFS(static))
	mux.HandleFunc("GET /ws", hub.serveWS(roleWeb))
	mux.HandleFunc("GET /api/info", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(info)
	})
	return securityHeaders(mux)
}

// newCallHandler is the public TLS side: only the Android signaling socket.
func newCallHandler(hub *Hub) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /ws", hub.serveWS(roleAndroid))
	mux.HandleFunc("/", func(w http.ResponseWriter, _ *http.Request) {
		http.Error(w, "videocall signaling endpoint", http.StatusNotFound)
	})
	return mux
}

func securityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h := w.Header()
		h.Set("Content-Security-Policy",
			"default-src 'self'; connect-src 'self'; media-src 'self' blob: mediastream:; "+
				"img-src 'self' data:; style-src 'self'; script-src 'self'; frame-ancestors 'none'; base-uri 'none'")
		h.Set("X-Content-Type-Options", "nosniff")
		h.Set("Referrer-Policy", "no-referrer")
		h.Set("X-Frame-Options", "DENY")
		h.Set("Permissions-Policy", "camera=(self), microphone=(self)")
		h.Set("Cache-Control", "no-store")
		next.ServeHTTP(w, r)
	})
}
