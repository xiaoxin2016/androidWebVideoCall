package main

import (
	"bufio"
	"crypto/tls"
	"errors"
	"net"
	"sync"
	"time"
)

// demux splits the call port's TCP listener by the first byte the client sends:
//
//	0x16        TLS handshake record (RFC 8446 §5.1)  -> WSS signaling
//	0x00..0x01  STUN message (RFC 8489 §5); a TURN-over-TCP client always
//	            starts with an Allocate request           -> TURN
//
// Anything else is closed.
type demux struct {
	ln   net.Listener
	tls  *chanListener
	turn *chanListener
}

func newDemux(ln net.Listener) *demux {
	return &demux{
		ln:   ln,
		tls:  newChanListener(ln.Addr()),
		turn: newChanListener(ln.Addr()),
	}
}

func (d *demux) run() {
	for {
		c, err := d.ln.Accept()
		if err != nil {
			var ne net.Error
			if errors.As(err, &ne) && ne.Timeout() {
				time.Sleep(50 * time.Millisecond)
				continue
			}
			return
		}
		go d.classify(c)
	}
}

func (d *demux) classify(c net.Conn) {
	if tc, ok := c.(*net.TCPConn); ok {
		_ = tc.SetNoDelay(true)
	}
	_ = c.SetReadDeadline(time.Now().Add(10 * time.Second))
	br := bufio.NewReader(c)
	b, err := br.Peek(1)
	_ = c.SetReadDeadline(time.Time{})
	if err != nil {
		_ = c.Close()
		return
	}
	pc := &peekedConn{Conn: c, r: br}
	switch first := b[0]; {
	case first == 0x16:
		d.tls.push(pc)
	case first <= 0x01:
		d.turn.push(pc)
	default:
		_ = c.Close()
	}
}

func (d *demux) close() error {
	d.tls.Close()
	d.turn.Close()
	return d.ln.Close()
}

type peekedConn struct {
	net.Conn
	r *bufio.Reader
}

func (p *peekedConn) Read(b []byte) (int, error) { return p.r.Read(b) }

type chanListener struct {
	ch     chan net.Conn
	addr   net.Addr
	done   chan struct{}
	closer sync.Once
}

func newChanListener(addr net.Addr) *chanListener {
	return &chanListener{ch: make(chan net.Conn, 64), addr: addr, done: make(chan struct{})}
}

func (l *chanListener) push(c net.Conn) {
	select {
	case l.ch <- c:
	case <-l.done:
		_ = c.Close()
	case <-time.After(5 * time.Second):
		_ = c.Close()
	}
}

func (l *chanListener) Accept() (net.Conn, error) {
	select {
	case c := <-l.ch:
		return c, nil
	case <-l.done:
		return nil, net.ErrClosed
	}
}

func (l *chanListener) Close() error {
	l.closer.Do(func() { close(l.done) })
	return nil
}

func (l *chanListener) Addr() net.Addr { return l.addr }

func tlsListener(inner net.Listener, cfg *tls.Config) net.Listener {
	return tls.NewListener(inner, cfg)
}
