package main

import (
	"net"
	"testing"
	"time"

	"github.com/pion/turn/v5"
)

func turnAllocCfg() turn.AllocateListenerConfig {
	return turn.AllocateListenerConfig{Network: "udp4"}
}

func TestDemuxClassify(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	d := newDemux(ln)
	go d.run()
	defer d.close()

	cases := []struct {
		first byte
		want  *chanListener
	}{
		{0x16, d.tls},
		{0x00, d.turn},
		{0x01, d.turn},
	}
	for _, c := range cases {
		conn, err := net.Dial("tcp", ln.Addr().String())
		if err != nil {
			t.Fatal(err)
		}
		_, _ = conn.Write([]byte{c.first, 0x03, 0x01})
		select {
		case got := <-c.want.ch:
			buf := make([]byte, 3)
			if _, err := got.Read(buf); err != nil || buf[0] != c.first {
				t.Fatalf("first byte %#x not replayed: %v %v", c.first, buf, err)
			}
			got.Close()
		case <-time.After(2 * time.Second):
			t.Fatalf("first byte %#x not routed to the expected listener", c.first)
		}
		conn.Close()
	}
}

func TestTurnURLs(t *testing.T) {
	h := &Hub{udpEnabled: true, callPort: 8443}
	cases := []struct {
		listener, host string
		want           []string
	}{
		{roleAndroid, "example.com:9443", []string{"turn:example.com:9443?transport=udp", "turn:example.com:9443?transport=tcp"}},
		{roleAndroid, "[::1]:8443", []string{"turn:[::1]:8443?transport=udp", "turn:[::1]:8443?transport=tcp"}},
		{roleAndroid, "example.com", []string{"turn:example.com:443?transport=udp", "turn:example.com:443?transport=tcp"}},
		// web: console host, call port
		{roleWeb, "localhost:8080", []string{"turn:localhost:8443?transport=udp", "turn:localhost:8443?transport=tcp"}},
	}
	for _, c := range cases {
		got := h.turnURLs(&client{listener: c.listener, host: c.host})
		if len(got) != len(c.want) || got[0] != c.want[0] || got[1] != c.want[1] {
			t.Errorf("turnURLs(%s, %q) = %v, want %v", c.listener, c.host, got, c.want)
		}
	}
}

func TestPasswordHash(t *testing.T) {
	h, err := hashPassword("correct horse")
	if err != nil {
		t.Fatal(err)
	}
	if !verifyPassword(h, "correct horse") {
		t.Fatal("correct password rejected")
	}
	if verifyPassword(h, "correct horsE") || verifyPassword(h, "") {
		t.Fatal("wrong password accepted")
	}
	h2, _ := hashPassword("correct horse")
	if h == h2 {
		t.Fatal("hash is not salted")
	}
	for _, bad := range []string{"", "plain", "$argon2id$v=19$m=999999999,t=1,p=1$AAAA$AAAA"} {
		if verifyPassword(bad, "x") {
			t.Fatalf("malformed hash %q accepted", bad)
		}
	}
	if validatePassword("short") == nil {
		t.Fatal("short password accepted")
	}
}

func TestConfigPersistsOnlyHash(t *testing.T) {
	dir := t.TempDir()
	s, created, err := loadOrCreateConfig(dir + "/config.json")
	if err != nil || !created {
		t.Fatalf("create: %v %v", created, err)
	}
	if err := s.setPasswordHash("$argon2id$test"); err != nil {
		t.Fatal(err)
	}
	s2, created, err := loadOrCreateConfig(dir + "/config.json")
	if err != nil || created {
		t.Fatalf("reload: %v %v", created, err)
	}
	if s2.cfg.PasswordHash != "$argon2id$test" || s2.cfg.ConsoleListen != defaultConsoleListen {
		t.Fatalf("unexpected config %+v", s2.cfg)
	}
}

func TestAuthLimiter(t *testing.T) {
	l := &authLimiter{m: map[string]*limitEntry{}}
	for i := 0; i < 4; i++ {
		l.fail("1.2.3.4")
	}
	if l.blocked("1.2.3.4") > 0 {
		t.Fatal("blocked too early")
	}
	l.fail("1.2.3.4")
	if l.blocked("1.2.3.4") <= 0 {
		t.Fatal("not blocked after 5 failures")
	}
	l.success("1.2.3.4")
	if l.blocked("1.2.3.4") > 0 {
		t.Fatal("still blocked after success")
	}
}

func TestGuardedRelay(t *testing.T) {
	g := newGuardedRelayGen()
	if err := g.Validate(); err != nil {
		t.Fatal(err)
	}
	a, _, err := g.AllocatePacketConn(turnAllocCfg())
	if err != nil {
		t.Fatal(err)
	}
	defer a.Close()
	port := a.LocalAddr().(*net.UDPAddr).Port
	if !g.allowed(&net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: port}) {
		t.Fatal("own relay port should be allowed")
	}
	if g.allowed(&net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 53}) {
		t.Fatal("arbitrary loopback port must be denied")
	}
	if g.allowed(&net.UDPAddr{IP: net.IPv4(10, 0, 0, 1), Port: port}) {
		t.Fatal("non-loopback must be denied")
	}
	a.Close()
	if g.allowed(&net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: port}) {
		t.Fatal("closed relay port should be denied")
	}
}
