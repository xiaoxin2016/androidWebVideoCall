// Command videocall-server is a self-hosted 1:1 video call server.
//
// Two listeners:
//
//   - the call port (public): TLS signaling for the Android client plus the
//     embedded TURN relay, demultiplexed on one TCP port, and TURN over UDP
//     on the same port number;
//   - the console port (plain HTTP, default 127.0.0.1:8080): the web UI,
//     which is the other call party. Restrict it with the bind address,
//     the host firewall or a reverse proxy.
//
// Media between the browser and the Android client is end-to-end DTLS-SRTP;
// the TURN relay only forwards ciphertext.
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

	var (
		dataDir  = flag.String("data", defaultDataDir(), "data directory (config.json, auto-generated TLS certificate)")
		listen   = flag.String("listen", "", "call port, public (TLS signaling + TURN, TCP and UDP), default "+defaultListen)
		console  = flag.String("console", "", "web console listen address (plain HTTP), default "+defaultConsoleListen)
		password = flag.String("password", "", "Android access password for this run only (or env VIDEOCALL_PASSWORD); normally set it in the web console")
		certFile = flag.String("cert", "", "TLS certificate PEM (optional; default: auto-generated self-signed)")
		keyFile  = flag.String("key", "", "TLS private key PEM (used with -cert)")
		hosts    = flag.String("hosts", "", "extra DNS names / IPs for the self-signed certificate, comma separated")
		noUDP    = flag.Bool("no-udp", false, "disable TURN over UDP (TCP only)")
		openUI   = flag.String("open", "", "open the web console in the local browser on start: true/false")
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
	store, created, err := loadOrCreateConfig(filepath.Join(*dataDir, "config.json"))
	if err != nil {
		log.Fatalf("config: %v", err)
	}
	if created {
		log.Printf("已生成默认配置文件: %s", store.path)
	}

	// Effective settings: flags > env > config file. Flag values are never
	// written back to config.json.
	cfg := store.cfg
	set := map[string]bool{}
	flag.Visit(func(f *flag.Flag) { set[f.Name] = true })
	if set["listen"] {
		cfg.Listen = *listen
	}
	if set["console"] {
		cfg.ConsoleListen = *console
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
	pw := os.Getenv("VIDEOCALL_PASSWORD")
	if set["password"] {
		pw = *password
	}
	if pw != "" {
		if err := validatePassword(pw); err != nil {
			log.Fatalf("-password: %v", err)
		}
		if cfg.PasswordHash, err = hashPassword(pw); err != nil {
			log.Fatal(err)
		}
		log.Printf("使用启动参数/环境变量提供的接入密码（仅本次运行，不写入配置）")
	}

	tlsCfg, fingerprint, err := loadTLS(&cfg, *dataDir)
	if err != nil {
		log.Fatalf("TLS: %v", err)
	}

	// ---- call port ----
	callLn, err := net.Listen("tcp", cfg.Listen)
	if err != nil {
		log.Fatalf("listen tcp %s: %v", cfg.Listen, err)
	}
	callPort := callLn.Addr().(*net.TCPAddr).Port
	var udpConn net.PacketConn
	if !cfg.DisableUDP {
		host, _, _ := net.SplitHostPort(cfg.Listen)
		udpConn, err = net.ListenPacket("udp4", net.JoinHostPort(host, strconv.Itoa(callPort)))
		if err != nil {
			log.Printf("警告: UDP %d 监听失败(%v)，仅使用 TCP 中继", callPort, err)
			udpConn = nil
		}
	}

	// ---- console port ----
	consoleLn, err := net.Listen("tcp", cfg.ConsoleListen)
	if err != nil {
		log.Fatalf("listen console %s: %v", cfg.ConsoleListen, err)
	}
	consolePort := consoleLn.Addr().(*net.TCPAddr).Port

	secret := make([]byte, 32)
	if _, err := rand.Read(secret); err != nil {
		log.Fatal(err)
	}
	turnSecret := hex.EncodeToString(secret)

	mux := newDemux(callLn)
	turnSrv, err := startTURN(mux.turn, udpConn, turnSecret)
	if err != nil {
		log.Fatalf("TURN: %v", err)
	}

	hub := newHub(hubOptions{
		passwordHash: cfg.PasswordHash,
		persist:      store.setPasswordHash,
		turnSecret:   turnSecret,
		callPort:     callPort,
		udp:          udpConn != nil,
	})
	info := serverInfo{
		Fingerprint: fingerprint,
		Version:     version,
		CallPort:    callPort,
		ConsolePort: consolePort,
		UDP:         udpConn != nil,
		LocalIPs:    localIPs(),
	}

	callSrv := &http.Server{
		Handler:           newCallHandler(hub),
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       120 * time.Second,
		ErrorLog:          log.New(&quietTLSLog{}, "", 0),
	}
	consoleSrv := &http.Server{
		Handler:           newConsoleHandler(hub, info),
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       120 * time.Second,
	}
	go func() {
		if err := callSrv.Serve(tlsListener(mux.tls, tlsCfg)); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Printf("call port: %v", err)
		}
	}()
	go func() {
		if err := consoleSrv.Serve(consoleLn); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Printf("console: %v", err)
		}
	}()
	go mux.run()

	consoleURL := fmt.Sprintf("http://%s/", consoleHostPort(cfg.ConsoleListen, consolePort))
	printBanner(info, consoleURL, hub.passwordSet())

	if cfg.OpenBrowser == nil || *cfg.OpenBrowser {
		go openBrowser(consoleURL)
	}

	sig := make(chan os.Signal, 1)
	signal.Notify(sig, os.Interrupt, syscall.SIGTERM)
	<-sig
	log.Println("正在关闭...")
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	hub.closeAll()
	_ = callSrv.Shutdown(ctx)
	_ = consoleSrv.Shutdown(ctx)
	_ = mux.close()
	_ = turnSrv.Close()
}

// consoleHostPort turns a listen address into something a local browser can open.
func consoleHostPort(listen string, port int) string {
	host, _, _ := net.SplitHostPort(listen)
	if ip := net.ParseIP(host); host == "" || (ip != nil && (ip.IsUnspecified() || ip.IsLoopback())) {
		host = "localhost"
	}
	return net.JoinHostPort(host, strconv.Itoa(port))
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

func printBanner(info serverInfo, consoleURL string, passwordSet bool) {
	b := &strings.Builder{}
	fmt.Fprintf(b, "\n================ videocall-server %s ================\n", version)
	fmt.Fprintf(b, "Web 控制台    : %s\n", consoleURL)
	fmt.Fprintf(b, "通话端口      : TCP %d", info.CallPort)
	if info.UDP {
		fmt.Fprintf(b, " + UDP %d", info.CallPort)
	}
	b.WriteString("（需对外开放 / 端口转发）\n")
	if len(info.LocalIPs) > 0 {
		fmt.Fprintf(b, "本机地址      : %s\n", strings.Join(info.LocalIPs, ", "))
	}
	if passwordSet {
		b.WriteString("接入密码      : 已设置\n")
	} else {
		b.WriteString("接入密码      : 未设置（请在 Web 控制台设置）\n")
	}
	fmt.Fprintf(b, "证书 SHA-256  : %s\n", info.Fingerprint)
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
// scanners and clients rejecting the self-signed certificate.
type quietTLSLog struct{}

func (quietTLSLog) Write(p []byte) (int, error) {
	if strings.Contains(string(p), "TLS handshake error") && os.Getenv("VIDEOCALL_DEBUG") == "" {
		return len(p), nil
	}
	log.Print(string(p))
	return len(p), nil
}
