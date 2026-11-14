package main

import (
	"embed"
	"encoding/json"
	"io/fs"
	"net"
	"net/http"
)

//go:embed web
var webFS embed.FS

func newHTTPHandler(hub *Hub, passwordRequired bool, fp string) http.Handler {
	static, _ := fs.Sub(webFS, "web")
	mux := http.NewServeMux()
	mux.Handle("GET /", http.FileServerFS(static))
	mux.HandleFunc("GET /ws", hub.serveWS)
	mux.HandleFunc("GET /api/info", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"passwordRequired": passwordRequired,
			"fingerprint":      fp,
			"version":          version,
		})
	})
	return securityHeaders(mux)
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

// loopbackOnly serves plain HTTP only to local clients (http://localhost is
// a secure context, so the browser on the server machine needs no certificate
// warning). Anyone else is redirected to HTTPS on the same host:port.
func loopbackOnly(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		host, _, _ := net.SplitHostPort(r.RemoteAddr)
		if ip := net.ParseIP(host); ip != nil && ip.IsLoopback() {
			next.ServeHTTP(w, r)
			return
		}
		http.Redirect(w, r, "https://"+r.Host+r.URL.RequestURI(), http.StatusMovedPermanently)
	})
}
