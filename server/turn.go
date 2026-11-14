package main

import (
	"errors"
	"log"
	"net"
	"sync"

	"github.com/pion/logging"
	"github.com/pion/turn/v5"
)

const turnRealm = "videocall"

// startTURN runs the embedded TURN server.
//
// Every relay socket is bound to 127.0.0.1, so the two call parties' relayed
// candidates meet on the server's loopback interface and no extra port has to
// be exposed. To prevent the relay from being abused as a pivot into the
// server host or its LAN (RFC 8656 §21), peers are restricted to loopback and
// the relay sockets refuse to send to anything except another live relay
// allocation of this server.
func startTURN(tcp net.Listener, udp net.PacketConn, secret string) (*turn.Server, error) {
	lf := logging.NewDefaultLoggerFactory()
	lf.DefaultLogLevel = logging.LogLevelWarn

	gen := newGuardedRelayGen()
	perm := func(_ net.Addr, peerIP net.IP) bool { return peerIP.IsLoopback() }

	cfg := turn.ServerConfig{
		Realm:         turnRealm,
		AuthHandler:   turn.NewLongTermAuthHandler(secret, lf.NewLogger("turn-auth")),
		LoggerFactory: lf,
		ListenerConfigs: []turn.ListenerConfig{{
			Listener:              tcp,
			RelayAddressGenerator: gen,
			PermissionHandler:     perm,
		}},
	}
	if udp != nil {
		cfg.PacketConnConfigs = []turn.PacketConnConfig{{
			PacketConn:            udp,
			RelayAddressGenerator: gen,
			PermissionHandler:     perm,
		}}
	}
	return turn.NewServer(cfg)
}

type guardedRelayGen struct {
	static *turn.RelayAddressGeneratorStatic
	mu     sync.Mutex
	ports  map[int]struct{}
}

func newGuardedRelayGen() *guardedRelayGen {
	return &guardedRelayGen{
		static: &turn.RelayAddressGeneratorStatic{RelayAddress: net.IPv4(127, 0, 0, 1), Address: "127.0.0.1"},
		ports:  map[int]struct{}{},
	}
}

func (g *guardedRelayGen) Validate() error { return g.static.Validate() }

func (g *guardedRelayGen) AllocatePacketConn(c turn.AllocateListenerConfig) (net.PacketConn, net.Addr, error) {
	if c.Network != "udp4" && c.Network != "udp" {
		return nil, nil, errors.New("only IPv4 UDP relays are supported")
	}
	pc, addr, err := g.static.AllocatePacketConn(c)
	if err != nil {
		return nil, nil, err
	}
	port := pc.LocalAddr().(*net.UDPAddr).Port
	g.mu.Lock()
	g.ports[port] = struct{}{}
	g.mu.Unlock()
	return &guardedPacketConn{PacketConn: pc, gen: g, port: port}, addr, nil
}

// TCP relays (RFC 6062) are not needed for WebRTC and are disabled.
func (g *guardedRelayGen) AllocateListener(turn.AllocateListenerConfig) (net.Listener, net.Addr, error) {
	return nil, nil, errors.New("TCP relay disabled")
}

func (g *guardedRelayGen) AllocateConn(turn.AllocateConnConfig) (net.Conn, error) {
	return nil, errors.New("TCP relay disabled")
}

func (g *guardedRelayGen) allowed(addr net.Addr) bool {
	ua, ok := addr.(*net.UDPAddr)
	if !ok || !ua.IP.IsLoopback() {
		return false
	}
	g.mu.Lock()
	_, ok = g.ports[ua.Port]
	g.mu.Unlock()
	return ok
}

type guardedPacketConn struct {
	net.PacketConn
	gen  *guardedRelayGen
	port int
	once sync.Once
}

func (p *guardedPacketConn) WriteTo(b []byte, addr net.Addr) (int, error) {
	if !p.gen.allowed(addr) {
		// Stale loopback ports (the other side just hung up) are dropped
		// silently; anything else is a relay abuse attempt worth logging.
		if ua, ok := addr.(*net.UDPAddr); !ok || !ua.IP.IsLoopback() {
			log.Printf("TURN: 已拦截发往非本服务中继地址的数据 %v", addr)
		}
		return len(b), nil
	}
	return p.PacketConn.WriteTo(b, addr)
}

func (p *guardedPacketConn) Close() error {
	p.once.Do(func() {
		p.gen.mu.Lock()
		delete(p.gen.ports, p.port)
		p.gen.mu.Unlock()
	})
	return p.PacketConn.Close()
}
