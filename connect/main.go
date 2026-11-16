package main

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/url"
	"os"
	"os/signal"
	"strconv"
	"sync"
	"syscall"
	"time"

	"github.com/tailscale/tailcat"
	"tailscale.com/types/key"
	"tailscale.com/wgengine/filter"
)

const (
	gamePort       = 25565
	gameplayLimit  = 10
	connectTimeout = 30 * time.Second
)

type hostConfig struct {
	MinecraftPort uint16           `json:"minecraft_port"`
	AllowedKeys   []key.NodePublic `json:"allowed_keys"`
	DERPMapURL    string           `json:"derp_map_url"`
}

type joinConfig struct {
	Address   tailcat.Addr    `json:"address"`
	Key       key.NodePrivate `json:"private_key"`
	LocalPort uint16          `json:"local_port"`
}

type accessUpdate struct {
	AllowedKeys *[]key.NodePublic `json:"allowed_keys"`
}

func main() {
	log.SetFlags(0)
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if err := run(ctx, os.Args[1:], os.Stdin, os.Stdout); err != nil {
		log.Print(err)
		os.Exit(1)
	}
}

func run(ctx context.Context, args []string, in io.Reader, out io.Writer) error {
	if len(args) != 1 {
		return errors.New("usage: lectern-connect keygen | host | join")
	}
	output := json.NewEncoder(out)
	if args[0] == "keygen" {
		private := key.NewNode()
		return output.Encode(map[string]any{"private_key": private, "public_key": private.Public()})
	}
	if args[0] != "host" && args[0] != "join" {
		return errors.New("usage: lectern-connect keygen | host | join")
	}

	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	requests := make(chan []byte)
	go readRequests(ctx, cancel, in, requests)
	var first []byte
	select {
	case first = <-requests:
	case <-ctx.Done():
		return nil
	}
	if args[0] == "host" {
		var config hostConfig
		if json.Unmarshal(first, &config) != nil {
			return errors.New("invalid host configuration")
		}
		return host(ctx, config, requests, output)
	}
	var config joinConfig
	if json.Unmarshal(first, &config) != nil {
		return errors.New("invalid join configuration")
	}
	return join(ctx, config, output)
}

// EOF is also a shutdown signal, including when the supervising process dies.
func readRequests(ctx context.Context, cancel context.CancelFunc, in io.Reader, requests chan<- []byte) {
	defer cancel()
	scanner := bufio.NewScanner(in)
	for scanner.Scan() {
		line := append([]byte(nil), scanner.Bytes()...)
		select {
		case requests <- line:
		case <-ctx.Done():
			return
		}
	}
}

func host(ctx context.Context, config hostConfig, requests <-chan []byte, output *json.Encoder) error {
	if config.MinecraftPort == 0 {
		return errors.New("a local Minecraft port is required")
	}
	if config.DERPMapURL != "" {
		u, err := url.Parse(config.DERPMapURL)
		if err != nil || u.Scheme != "https" || u.Hostname() == "" || u.User != nil || u.Fragment != "" {
			return errors.New("the DERP map override must be an HTTPS URL")
		}
	}

	access := newGameAccess(config.AllowedKeys)
	target := net.JoinHostPort("127.0.0.1", strconv.Itoa(int(config.MinecraftPort)))
	server := &tailcat.Server{
		DERPMapURL:     config.DERPMapURL,
		Logf:           quietLog,
		AllowClient:    access.allows,
		ServedTCPPorts: []filter.PortRange{{First: gamePort, Last: gamePort}},
	}
	server.OnTCP = func(port uint16) func(net.Conn) {
		if port != gamePort {
			return nil
		}
		return func(conn net.Conn) {
			defer conn.Close()
			peer, ok := server.PeerKey(conn.RemoteAddr())
			if !ok {
				return
			}
			stream, release, ok := access.reserve(ctx, peer)
			if !ok {
				return
			}
			defer release()
			dialer := net.Dialer{Timeout: connectTimeout}
			minecraft, err := dialer.DialContext(stream, "tcp4", target)
			if err == nil {
				bridge(stream, conn, minecraft)
			}
		}
	}

	startup, cancel := context.WithTimeout(ctx, connectTimeout)
	err := server.StartContext(startup)
	cancel()
	if err != nil {
		return fmt.Errorf("start the gameplay tunnel: %w", err)
	}
	defer server.Close()
	if err := output.Encode(map[string]any{"event": "ready", "address": server.TailcatAddr()}); err != nil {
		return err
	}
	for {
		select {
		case <-ctx.Done():
			return nil
		case request := <-requests:
			var update accessUpdate
			if json.Unmarshal(request, &update) != nil || update.AllowedKeys == nil {
				return errors.New("invalid access update")
			}
			removedKeys, revokedStreams := access.replace(*update.AllowedKeys)
			drainRevoked(ctx, server, revokedStreams)
			for _, removed := range removedKeys {
				server.DisconnectClient(removed)
			}
			if err := output.Encode(map[string]string{"event": "access_updated"}); err != nil {
				return err
			}
		}
	}
}

// Keep the peer route long enough to deliver TCP closes. Admission and new
// game connections are already blocked by the updated policy.
func drainRevoked(ctx context.Context, server *tailcat.Server, streams []<-chan struct{}) {
	if len(streams) == 0 {
		return
	}
	ctx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	for _, done := range streams {
		select {
		case <-done:
		case <-ctx.Done():
			return
		}
	}
	_ = server.DrainTCP(ctx)
}

func join(ctx context.Context, config joinConfig, output *json.Encoder) error {
	if config.Address == "" || config.Key.IsZero() {
		return errors.New("a tunnel address and device key are required")
	}
	if _, err := tailcat.ParseAddr(config.Address); err != nil {
		return errors.New("invalid tunnel address")
	}
	client := &tailcat.Client{Server: config.Address, Key: config.Key, Logf: quietLog}
	defer client.Close()
	startup, cancel := context.WithTimeout(ctx, connectTimeout)
	_, err := client.Ping(startup)
	cancel()
	if err != nil {
		return errors.New("could not reach the gameplay tunnel with this device")
	}

	addr := net.JoinHostPort("127.0.0.1", strconv.Itoa(int(config.LocalPort)))
	listener, err := net.Listen("tcp4", addr)
	if err != nil {
		return fmt.Errorf("open the local gameplay port: %w", err)
	}
	defer listener.Close()
	stop := context.AfterFunc(ctx, func() { listener.Close() })
	defer stop()
	if err := output.Encode(map[string]string{"event": "ready", "local_address": listener.Addr().String()}); err != nil {
		return err
	}

	for {
		local, err := listener.Accept()
		if err != nil {
			if ctx.Err() != nil {
				return nil
			}
			return err
		}
		go func() {
			defer local.Close()
			dial, cancel := context.WithTimeout(ctx, connectTimeout)
			upstream, err := client.DialTCPPort(dial, gamePort)
			cancel()
			if err == nil {
				bridge(ctx, local, upstream)
			}
		}()
	}
}

func quietLog(string, ...any) {}

func bridge(ctx context.Context, a, b net.Conn) {
	defer a.Close()
	defer b.Close()
	stop := context.AfterFunc(ctx, func() {
		a.Close()
		b.Close()
	})
	defer stop()
	done := make(chan struct{})
	go func() {
		copyStream(a, b)
		close(done)
	}()
	copyStream(b, a)
	<-done
}

func copyStream(dst, src net.Conn) {
	if _, err := io.Copy(dst, src); err != nil {
		dst.Close()
		src.Close()
		return
	}
	if half, ok := dst.(interface{ CloseWrite() error }); ok && half.CloseWrite() == nil {
		return
	}
	dst.Close()
}

type gameAccess struct {
	mu      sync.Mutex
	allowed map[key.NodePublic]bool
	streams map[*gameStream]bool
}

type gameStream struct {
	peer   key.NodePublic
	cancel context.CancelFunc
	done   chan struct{}
}

func newGameAccess(keys []key.NodePublic) *gameAccess {
	return &gameAccess{allowed: allowedSet(keys), streams: make(map[*gameStream]bool)}
}

func allowedSet(keys []key.NodePublic) map[key.NodePublic]bool {
	allowed := make(map[key.NodePublic]bool, len(keys))
	for _, k := range keys {
		if !k.IsZero() {
			allowed[k] = true
		}
	}
	return allowed
}

func (a *gameAccess) allows(peer key.NodePublic) bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.allowed[peer]
}

func (a *gameAccess) reserve(ctx context.Context, peer key.NodePublic) (context.Context, func(), bool) {
	a.mu.Lock()
	defer a.mu.Unlock()
	if !a.allowed[peer] || len(a.streams) >= gameplayLimit {
		return nil, nil, false
	}
	stream, cancel := context.WithCancel(ctx)
	record := &gameStream{peer: peer, cancel: cancel, done: make(chan struct{})}
	a.streams[record] = true
	return stream, func() {
		cancel()
		a.mu.Lock()
		delete(a.streams, record)
		close(record.done)
		a.mu.Unlock()
	}, true
}

func (a *gameAccess) replace(keys []key.NodePublic) ([]key.NodePublic, []<-chan struct{}) {
	a.mu.Lock()
	defer a.mu.Unlock()
	allowed := allowedSet(keys)
	var removed []key.NodePublic
	for peer := range a.allowed {
		if !allowed[peer] {
			removed = append(removed, peer)
		}
	}
	a.allowed = allowed
	var revokedStreams []<-chan struct{}
	for record := range a.streams {
		if !allowed[record.peer] {
			record.cancel()
			revokedStreams = append(revokedStreams, record.done)
		}
	}
	return removed, revokedStreams
}
