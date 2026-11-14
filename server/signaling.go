package main

import (
	"encoding/json"
	"log"
	"net"
	"net/http"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"github.com/gorilla/websocket"
	"github.com/pion/turn/v5"
)

const (
	roleWeb     = "web"
	roleAndroid = "android"

	authTimeout   = 10 * time.Second
	pingInterval  = 20 * time.Second
	pongWait      = 45 * time.Second
	writeWait     = 10 * time.Second
	maxMsgSize    = 64 << 10
	turnCredTTL   = 24 * time.Hour
	maxNameLength = 32
)

type callState int

const (
	stateIdle callState = iota
	stateRinging
	stateInCall
)

// Hub tracks at most one web client and one Android client and the call
// state between them. All signaling is relayed only between those two.
// The role is fixed by the listener a socket arrived on: the console port
// yields "web", the public call port yields "android".
type Hub struct {
	turnSecret string
	callPort   int
	udpEnabled bool
	persist    func(hash string) error
	limiter    *authLimiter

	pwMu         sync.RWMutex
	passwordHash string

	mu      sync.Mutex
	clients map[string]*client
	state   callState
}

type hubOptions struct {
	passwordHash string
	persist      func(hash string) error
	turnSecret   string
	callPort     int
	udp          bool
}

func newHub(o hubOptions) *Hub {
	return &Hub{
		passwordHash: o.passwordHash,
		persist:      o.persist,
		turnSecret:   o.turnSecret,
		callPort:     o.callPort,
		udpEnabled:   o.udp,
		limiter:      newAuthLimiter(),
		clients:      map[string]*client{},
	}
}

func (h *Hub) passwordSet() bool {
	h.pwMu.RLock()
	defer h.pwMu.RUnlock()
	return h.passwordHash != ""
}

func (h *Hub) currentHash() string {
	h.pwMu.RLock()
	defer h.pwMu.RUnlock()
	return h.passwordHash
}

type message struct {
	Type      string          `json:"type"`
	Role      string          `json:"role,omitempty"`
	Password  string          `json:"password,omitempty"`
	Name      string          `json:"name,omitempty"`
	Reason    string          `json:"reason,omitempty"`
	SDP       string          `json:"sdp,omitempty"`
	Candidate json.RawMessage `json:"candidate,omitempty"`
	Relay     string          `json:"relay,omitempty"` // netinfo: "udp" | "tcp"
}

type iceServer struct {
	URLs       []string `json:"urls"`
	Username   string   `json:"username"`
	Credential string   `json:"credential"`
}

type client struct {
	hub      *Hub
	conn     *websocket.Conn
	send     chan []byte
	role     string // set after successful auth
	listener string // role allowed by the listener the socket arrived on
	name     string
	ip       string
	host     string // Host header the client used, for building TURN URLs
	closeMu  sync.Once
	done     chan struct{}
}

var upgrader = websocket.Upgrader{
	ReadBufferSize:  4096,
	WriteBufferSize: 4096,
	// Default CheckOrigin: browsers must be same-origin; the Android client
	// sends no Origin header and is accepted.
}

func (h *Hub) serveWS(role string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		conn, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			return
		}
		ip, _, _ := net.SplitHostPort(r.RemoteAddr)
		c := &client{
			hub:      h,
			conn:     conn,
			send:     make(chan []byte, 64),
			listener: role,
			ip:       ip,
			host:     r.Host,
			done:     make(chan struct{}),
		}
		conn.SetReadLimit(maxMsgSize)
		go c.writePump()
		c.readPump()
	}
}

func (c *client) readPump() {
	defer c.hub.unregister(c)
	_ = c.conn.SetReadDeadline(time.Now().Add(authTimeout))
	c.conn.SetPongHandler(func(string) error {
		return c.conn.SetReadDeadline(time.Now().Add(pongWait))
	})

	authed := false
	for {
		var m message
		if err := c.conn.ReadJSON(&m); err != nil {
			return
		}
		if !authed {
			if m.Type != "auth" || !c.hub.authenticate(c, &m) {
				// give the write pump a moment to flush auth_fail
				time.Sleep(200 * time.Millisecond)
				return
			}
			authed = true
			_ = c.conn.SetReadDeadline(time.Now().Add(pongWait))
			continue
		}
		c.hub.handle(c, &m)
	}
}

func (c *client) writePump() {
	t := time.NewTicker(pingInterval)
	defer func() {
		t.Stop()
		_ = c.conn.Close()
	}()
	for {
		select {
		case b := <-c.send:
			_ = c.conn.SetWriteDeadline(time.Now().Add(writeWait))
			if err := c.conn.WriteMessage(websocket.TextMessage, b); err != nil {
				return
			}
		case <-t.C:
			_ = c.conn.SetWriteDeadline(time.Now().Add(writeWait))
			if err := c.conn.WriteMessage(websocket.PingMessage, nil); err != nil {
				return
			}
		case <-c.done:
			// flush what is queued, then close
			for {
				select {
				case b := <-c.send:
					_ = c.conn.SetWriteDeadline(time.Now().Add(writeWait))
					_ = c.conn.WriteMessage(websocket.TextMessage, b)
				default:
					_ = c.conn.WriteMessage(websocket.CloseMessage,
						websocket.FormatCloseMessage(websocket.CloseNormalClosure, ""))
					return
				}
			}
		}
	}
}

func (c *client) sendJSON(v any) {
	b, err := json.Marshal(v)
	if err != nil {
		return
	}
	select {
	case c.send <- b:
	case <-c.done:
	default:
		log.Printf("client %s(%s) send buffer full, dropping connection", c.role, c.ip)
		c.close()
	}
}

func (c *client) close() {
	c.closeMu.Do(func() { close(c.done) })
}

func (h *Hub) authenticate(c *client, m *message) bool {
	if m.Role != c.listener {
		c.sendJSON(map[string]string{"type": "auth_fail", "reason": "bad_role"})
		return false
	}
	if c.listener == roleAndroid {
		if wait := h.limiter.blocked(c.ip); wait > 0 {
			c.sendJSON(map[string]string{"type": "auth_fail", "reason": "rate_limited",
				"retry_after": strconv.Itoa(int(wait.Seconds()) + 1)})
			return false
		}
		if hash := h.currentHash(); hash != "" && !verifyPassword(hash, m.Password) {
			h.limiter.fail(c.ip)
			log.Printf("认证失败: ip=%s", c.ip)
			time.Sleep(500 * time.Millisecond)
			c.sendJSON(map[string]string{"type": "auth_fail", "reason": "bad_password"})
			return false
		}
		h.limiter.success(c.ip)
	}

	name := m.Name
	if !utf8.ValidString(name) {
		name = ""
	}
	if r := []rune(name); len(r) > maxNameLength {
		name = string(r[:maxNameLength])
	}
	if name == "" {
		name = m.Role
	}
	c.role, c.name = m.Role, name

	h.mu.Lock()
	if old := h.clients[c.role]; old != nil {
		if h.state != stateIdle {
			h.mu.Unlock()
			c.sendJSON(map[string]string{"type": "auth_fail", "reason": "busy"})
			return false
		}
		old.sendJSON(map[string]string{"type": "kicked"})
		old.close()
		delete(h.clients, c.role)
	}
	h.clients[c.role] = c
	peer := h.peerOf(c)
	h.mu.Unlock()

	user, cred, err := turn.GenerateLongTermCredentials(h.turnSecret, turnCredTTL)
	if err != nil {
		c.sendJSON(map[string]string{"type": "auth_fail", "reason": "internal"})
		return false
	}
	resp := map[string]any{
		"type":        "auth_ok",
		"iceServers":  []iceServer{{URLs: h.turnURLs(c), Username: user, Credential: cred}},
		"peerOnline":  peer != nil,
		"passwordSet": h.passwordSet(),
		"version":     version,
	}
	if peer != nil {
		resp["peerName"] = peer.name
	}
	c.sendJSON(resp)
	if peer != nil {
		peer.sendJSON(map[string]any{"type": "presence", "online": true, "name": c.name})
	}
	log.Printf("已连接: role=%s name=%q ip=%s", c.role, c.name, c.ip)
	return true
}

// turnURLs builds TURN URLs from the address the client itself used to reach
// us, so port forwarding and DNS names work without configuration:
//   - Android: the host:port it dialed on the call port;
//   - web: the host it opened the console with, plus the call port
//     (normally localhost, so media stays on loopback).
func (h *Hub) turnURLs(c *client) []string {
	host, port, err := net.SplitHostPort(c.host)
	if err != nil {
		host, port = c.host, "443"
	}
	if c.listener != roleWeb {
		return buildTurnURLs(host, port, h.udpEnabled)
	}
	// Web console: the browser runs on this machine. Chrome binds its TURN
	// client sockets to a network interface address and cannot reliably
	// reach a loopback TURN server from there (works on Linux, not on every
	// OS), so point it at this machine's own interface address first and keep
	// loopback only as a fallback.
	port = strconv.Itoa(h.callPort)
	if override := os.Getenv("VIDEOCALL_WEB_TURN_HOST"); override != "" {
		return buildTurnURLs(override, port, h.udpEnabled)
	}
	if !isLoopbackHost(host) {
		return buildTurnURLs(host, port, h.udpEnabled)
	}
	urls := []string{}
	if ip := outboundIPv4(); ip != "" {
		urls = buildTurnURLs(ip, port, h.udpEnabled)
	}
	return append(urls, buildTurnURLs("127.0.0.1", port, h.udpEnabled)...)
}

func isLoopbackHost(host string) bool {
	if strings.EqualFold(host, "localhost") {
		return true
	}
	ip := net.ParseIP(host)
	return ip != nil && ip.IsLoopback()
}

// outboundIPv4 returns the address of the interface holding the default
// route (no packet is sent), else the first non-loopback IPv4 address.
func outboundIPv4() string {
	if c, err := net.Dial("udp4", "192.0.2.1:9"); err == nil {
		defer c.Close()
		if ua, ok := c.LocalAddr().(*net.UDPAddr); ok && !ua.IP.IsLoopback() && !ua.IP.IsUnspecified() {
			return ua.IP.String()
		}
	}
	if ips := localIPs(); len(ips) > 0 {
		return ips[0]
	}
	return ""
}

func buildTurnURLs(host, port string, udp bool) []string {
	if ip := net.ParseIP(host); ip != nil && ip.To4() == nil {
		host = "[" + host + "]"
	}
	urls := []string{}
	if udp {
		urls = append(urls, "turn:"+host+":"+port+"?transport=udp")
	}
	return append(urls, "turn:"+host+":"+port+"?transport=tcp")
}

func (h *Hub) peerOf(c *client) *client {
	if c.role == roleWeb {
		return h.clients[roleAndroid]
	}
	return h.clients[roleWeb]
}

func (h *Hub) handle(c *client, m *message) {
	switch m.Type {
	case "set_password", "clear_password":
		if c.role == roleWeb {
			h.changePassword(c, m)
		}
		return
	}

	h.mu.Lock()
	defer h.mu.Unlock()
	if h.clients[c.role] != c {
		return // replaced
	}
	peer := h.peerOf(c)

	switch m.Type {
	case "call":
		if c.role != roleAndroid {
			return
		}
		if peer == nil {
			c.sendJSON(map[string]string{"type": "call_failed", "reason": "offline"})
			return
		}
		if h.state != stateIdle {
			c.sendJSON(map[string]string{"type": "call_failed", "reason": "busy"})
			return
		}
		h.state = stateRinging
		peer.sendJSON(map[string]any{"type": "incoming", "name": c.name, "ip": c.ip, "verified": h.passwordSet()})
		log.Printf("来电: %s", c.name)

	case "accept":
		if c.role != roleWeb || h.state != stateRinging || peer == nil {
			return
		}
		h.state = stateInCall
		peer.sendJSON(map[string]string{"type": "accepted"})
		log.Printf("已接听")

	case "reject":
		if c.role != roleWeb || h.state != stateRinging {
			return
		}
		h.state = stateIdle
		if peer != nil {
			peer.sendJSON(map[string]string{"type": "rejected"})
		}
		log.Printf("已拒接")

	case "hangup":
		if h.state == stateIdle {
			return
		}
		h.state = stateIdle
		if peer != nil {
			peer.sendJSON(map[string]string{"type": "hangup", "reason": "remote"})
		}
		log.Printf("通话结束 (%s 挂断)", c.role)

	case "offer", "answer", "candidate", "netinfo":
		if h.state != stateInCall || peer == nil {
			return
		}
		// Only Android offers; only web answers.
		if (m.Type == "offer" && c.role != roleAndroid) || (m.Type == "answer" && c.role != roleWeb) {
			return
		}
		if m.Type == "netinfo" && m.Relay != "udp" && m.Relay != "tcp" {
			return
		}
		peer.sendJSON(message{Type: m.Type, SDP: m.SDP, Candidate: m.Candidate, Relay: m.Relay})
	}
}

// changePassword updates the Android access password from the console.
// New connections use it immediately; an established session is unaffected.
func (h *Hub) changePassword(c *client, m *message) {
	hash := ""
	if m.Type == "set_password" {
		if err := validatePassword(m.Password); err != nil {
			c.sendJSON(map[string]any{"type": "password_status", "set": h.passwordSet(), "error": err.Error()})
			return
		}
		var err error
		if hash, err = hashPassword(m.Password); err != nil {
			c.sendJSON(map[string]any{"type": "password_status", "set": h.passwordSet(), "error": "internal error"})
			return
		}
	}
	if h.persist != nil {
		if err := h.persist(hash); err != nil {
			log.Printf("保存配置失败: %v", err)
			c.sendJSON(map[string]any{"type": "password_status", "set": h.passwordSet(), "error": "保存配置失败: " + err.Error()})
			return
		}
	}
	h.pwMu.Lock()
	h.passwordHash = hash
	h.pwMu.Unlock()
	if hash == "" {
		log.Printf("接入密码已清除")
	} else {
		log.Printf("接入密码已更新")
	}
	c.sendJSON(map[string]any{"type": "password_status", "set": hash != "", "changed": true})
}

func (h *Hub) unregister(c *client) {
	c.close()
	h.mu.Lock()
	defer h.mu.Unlock()
	if c.role == "" || h.clients[c.role] != c {
		return
	}
	delete(h.clients, c.role)
	peer := h.peerOf(c)
	if h.state != stateIdle {
		h.state = stateIdle
		if peer != nil {
			peer.sendJSON(map[string]string{"type": "hangup", "reason": "disconnected"})
		}
	}
	if peer != nil {
		peer.sendJSON(map[string]any{"type": "presence", "online": false})
	}
	log.Printf("已断开: role=%s ip=%s", c.role, c.ip)
}

func (h *Hub) closeAll() {
	h.mu.Lock()
	defer h.mu.Unlock()
	for _, c := range h.clients {
		c.close()
	}
}

// authLimiter: 5 consecutive failures from one IP lock it out, doubling from
// 1 minute up to 30 minutes.
type authLimiter struct {
	mu sync.Mutex
	m  map[string]*limitEntry
}

type limitEntry struct {
	fails   int
	strikes int
	until   time.Time
	seen    time.Time
}

func newAuthLimiter() *authLimiter {
	l := &authLimiter{m: map[string]*limitEntry{}}
	go func() {
		for range time.Tick(10 * time.Minute) {
			l.mu.Lock()
			for k, e := range l.m {
				if time.Since(e.seen) > time.Hour && time.Now().After(e.until) {
					delete(l.m, k)
				}
			}
			l.mu.Unlock()
		}
	}()
	return l
}

func (l *authLimiter) blocked(ip string) time.Duration {
	l.mu.Lock()
	defer l.mu.Unlock()
	if e := l.m[ip]; e != nil {
		return time.Until(e.until)
	}
	return 0
}

func (l *authLimiter) fail(ip string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	e := l.m[ip]
	if e == nil {
		e = &limitEntry{}
		l.m[ip] = e
	}
	e.seen = time.Now()
	e.fails++
	if e.fails >= 5 {
		e.fails = 0
		d := time.Minute << e.strikes
		if d > 30*time.Minute {
			d = 30 * time.Minute
		} else {
			e.strikes++
		}
		e.until = time.Now().Add(d)
		log.Printf("IP %s 认证失败次数过多，锁定 %v", ip, d)
	}
}

func (l *authLimiter) success(ip string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	delete(l.m, ip)
}
