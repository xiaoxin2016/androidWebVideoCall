// Command videocall-server is a self-hosted 1:1 video call server.
//
// A single TCP port carries HTTPS (web UI + WSS signaling), plain HTTP for
// loopback access, and TURN-over-TCP. The same UDP port number carries
// TURN-over-UDP. Media between the web UI and the Android client is
// end-to-end DTLS-SRTP; the embedded TURN server only relays ciphertext.
package main

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"flag"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"syscall"
	"time"
)

var version = "dev"

func main() {
	log.SetFlags(log.LstdFlags)

	defaultData := defaultDataDir()
	var (
		dataDir  = flag.String("data", defaultData, "data directory (config.json, auto-generated TLS certificate)")
		listen   = flag.String("listen", "", "listen address, e.g. :8443 (TCP and UDP use the same port)")
		password = flag.String("password", "", "access password (or env VIDEOCALL_PASSWORD); empty = no password")
		certFile = flag.String("cert", "", "TLS certificate PEM (optional; default: auto-generated self-signed)")
		keyFile  = flag.String("key", "", "TLS private key PEM (used with -cert)")
		hosts    = flag.String("hosts", "", "extra DNS names / IPs for the self-signed certificate, comma separated")
		noUDP    = flag.Bool("no-udp", false, "disable TURN over UDP (TCP only)")
		openUI   = flag.String("open", "", "open the web UI in the local browser on start: true/false")
		showVer  = flag.Bool("version", false, "print version and exit")
	)
	flag.Parse()
	if *showVer {
		fmt.Println(version)
		return
	}

	if err := os.MkdirAll(*dataDir, 0o700); err != nil {
		log.Fatalf("create data dir: %v", err)
	}
	cfgPath := filepath.Join(*dataDir, "config.json")
	cfg, created, err := loadOrCreateConfig(cfgPath)
	if err != nil {
		log.Fatalf("config: %v", err)
	}
	if created {
		log.Printf("已生成默认配置文件: %s", cfgPath)
	}

	// Precedence: flag > env > config file.
	set := map[string]bool{}
	flag.Visit(func(f *flag.Flag) { set[f.Name] = true })
	if set["listen"] {
		cfg.Listen = *listen
	}
	if v := os.Getenv("VIDEOCALL_PASSWORD"); v != "" {
		cfg.Password = v
	}
	if set["password"] {
		cfg.Password = *password
	}
	if set["cert"] {
		cfg.CertFile = *certFile
	}
	if set["key"] {
		cfg.KeyFile = *keyFile
	}
	if set["hosts"] {
		cfg.Hosts = splitList(*hosts)
	}
	if set["no-udp"] {
		cfg.DisableUDP = *noUDP
	}
	if set["open"] {
		b, _ := strconv.ParseBool(*openUI)
		cfg.OpenBrowser = &b
	}

	tlsCfg, fingerprint, err := loadTLS(cfg, *dataDir)
	if err != nil {
		log.Fatalf("TLS: %v", err)
	}

	tcpLn, err := net.Listen("tcp", cfg.Listen)
	if err != nil {
		log.Fatalf("listen tcp %s: %v", cfg.Listen, err)
	}
	port := tcpLn.Addr().(*net.TCPAddr).Port

	var udpConn net.PacketConn
	if !cfg.DisableUDP {
		host, _, _ := net.SplitHostPort(cfg.Listen)
		udpConn, err = net.ListenPacket("udp4", net.JoinHostPort(host, strconv.Itoa(port)))
		if err != nil {
			log.Printf("警告: UDP %d 监听失败(%v)，仅使用 TCP 中继", port, err)
			udpConn = nil
		}
	}

	mux := newDemux(tcpLn)

	secret := make([]byte, 32)
	if _, err := rand.Read(secret); err != nil {
		log.Fatal(err)
	}
	turnSecret := hex.EncodeToString(secret)
	turnSrv, err := startTURN(mux.turn, udpConn, turnSecret)
	if err != nil {
		log.Fatalf("TURN: %v", err)
	}

	hub := newHub(cfg.Password, turnSecret, fingerprint, udpConn != nil)
	handler := newHTTPHandler(hub, cfg.Password != "", fingerprint)

	httpsSrv := &http.Server{
		Handler:           handler,
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       120 * time.Second,
		ErrorLog:          log.New(&quietTLSLog{}, "", 0),
	}
	plainSrv := &http.Server{
		Handler:           loopbackOnly(handler),
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       120 * time.Second,
	}

	go func() {
		if err := httpsSrv.Serve(tlsListener(mux.tls, tlsCfg)); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Printf("https: %v", err)
		}
	}()
	go func() {
		if err := plainSrv.Serve(mux.http); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Printf("http: %v", err)
		}
	}()
	go mux.run()

	printBanner(cfg, port, fingerprint, udpConn != nil)

	if cfg.OpenBrowser == nil || *cfg.OpenBrowser {
		go openBrowser(fmt.Sprintf("http://localhost:%d/", port))
	}

	sig := make(chan os.Signal, 1)
	signal.Notify(sig, os.Interrupt, syscall.SIGTERM)
	<-sig
	log.Println("正在关闭...")
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	hub.closeAll()
	_ = httpsSrv.Shutdown(ctx)
	_ = plainSrv.Shutdown(ctx)
	_ = mux.close()
	_ = turnSrv.Close()
}

func defaultDataDir() string {
	if d, err := os.UserConfigDir(); err == nil {
		return filepath.Join(d, "videocall-server")
	}
	return "videocall-data"
}

func splitList(s string) []string {
	var out []string
	for _, p := range strings.Split(s, ",") {
		if p = strings.TrimSpace(p); p != "" {
			out = append(out, p)
		}
	}
	return out
}

func printBanner(cfg *Config, port int, fp string, udp bool) {
	b := &strings.Builder{}
	fmt.Fprintf(b, "\n================ videocall-server %s ================\n", version)
	fmt.Fprintf(b, "监听端口      : TCP %d", port)
	if udp {
		fmt.Fprintf(b, " + UDP %d", port)
	}
	b.WriteString("\n")
	fmt.Fprintf(b, "本机 Web 界面 : http://localhost:%d/\n", port)
	for _, ip := range localIPs() {
		fmt.Fprintf(b, "远程 Web 界面 : https://%s/\n", net.JoinHostPort(ip, strconv.Itoa(port)))
	}
	if cfg.Password == "" {
		b.WriteString("访问密码      : 未设置（任何能访问该端口的人都可以呼叫，建议设置）\n")
	} else {
		b.WriteString("访问密码      : 已设置\n")
	}
	fmt.Fprintf(b, "证书 SHA-256  : %s\n", fp)
	b.WriteString("Android 首次连接时请核对上面的证书指纹。\n")
	b.WriteString("====================================================\n")
	log.Print(b.String())
}

func localIPs() []string {
	var out []string
	addrs, err := net.InterfaceAddrs()
	if err != nil {
		return nil
	}
	for _, a := range addrs {
		ipn, ok := a.(*net.IPNet)
		if !ok || ipn.IP.IsLoopback() || ipn.IP.IsLinkLocalUnicast() {
			continue
		}
		if ipn.IP.To4() != nil {
			out = append(out, ipn.IP.String())
		}
	}
	return out
}

func openBrowser(url string) {
	time.Sleep(300 * time.Millisecond)
	var cmd *exec.Cmd
	switch runtime.GOOS {
	case "darwin":
		cmd = exec.Command("open", url)
	case "windows":
		cmd = exec.Command("rundll32", "url.dll,FileProtocolHandler", url)
	default:
		cmd = exec.Command("xdg-open", url)
	}
	if err := cmd.Start(); err == nil {
		go cmd.Wait() //nolint:errcheck
	}
}

// quietTLSLog drops the noisy "TLS handshake error" lines produced by
// scanners and by browsers rejecting the self-signed certificate.
type quietTLSLog struct{}

func (quietTLSLog) Write(p []byte) (int, error) {
	if strings.Contains(string(p), "TLS handshake error") && os.Getenv("VIDEOCALL_DEBUG") == "" {
		return len(p), nil
	}
	log.Print(string(p))
	return len(p), nil
}
