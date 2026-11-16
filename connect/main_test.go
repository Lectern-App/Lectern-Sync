package main

import (
	"context"
	"encoding/json"
	"io"
	"net"
	"os"
	"testing"
	"time"

	"github.com/tailscale/tailcat"
	"tailscale.com/types/key"
)

func TestGameplayAccess(t *testing.T) {
	player := key.NewNode().Public()
	guest := key.NewNode().Public()
	access := newGameAccess(nil)
	if access.allows(player) {
		t.Fatal("an empty allowlist must deny all devices")
	}
	access.replace([]key.NodePublic{player, guest})

	var last context.Context
	for i := 0; i < gameplayLimit; i++ {
		peer := player
		if i == gameplayLimit-1 {
			peer = guest
		}
		stream, release, ok := access.reserve(t.Context(), peer)
		if !ok {
			t.Fatalf("slot %d was refused", i)
		}
		t.Cleanup(release)
		last = stream
	}
	if _, _, ok := access.reserve(t.Context(), guest); ok {
		t.Fatal("a full server must refuse new connections")
	}
	removed, revoked := access.replace([]key.NodePublic{player})
	if len(removed) != 1 || removed[0] != guest || access.allows(guest) || last.Err() != context.Canceled {
		t.Fatal("revocation must close only the removed device's streams and reject reconnects")
	}
	if len(revoked) != 1 {
		t.Fatal("revocation must wait for the removed device's stream to close")
	}
}

func TestBridgeStopsOnCancellation(t *testing.T) {
	ctx, cancel := context.WithCancel(t.Context())
	defer cancel()
	a, local := net.Pipe()
	defer local.Close()
	b, upstream := net.Pipe()
	defer upstream.Close()
	done := make(chan struct{})
	go func() {
		bridge(ctx, a, b)
		close(done)
	}()
	cancel()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("bridge still running after revocation")
	}
}

// Exercise the actual Tailcat transport without launching Minecraft or mods.
// The two peers meet on the public DERP map, using an ephemeral loopback target.
func TestPublicDERPGameplay(t *testing.T) {
	if os.Getenv("LECTERN_CONNECT_LIVE_TEST") != "1" {
		t.Skip("set LECTERN_CONNECT_LIVE_TEST=1 to contact the public Tailcat service")
	}
	ctx, cancel := context.WithTimeout(t.Context(), 45*time.Second)
	defer cancel()
	target, err := net.ListenTCP("tcp4", &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer target.Close()
	go func() {
		conn, err := target.Accept()
		if err == nil {
			defer conn.Close()
			io.Copy(conn, conn)
		}
	}()

	device := key.NewNode()
	updates := make(chan []byte)
	hostEvent, hostDone := startTestHelper(t, func(out *json.Encoder) error {
		return host(ctx, hostConfig{
			MinecraftPort: uint16(target.Addr().(*net.TCPAddr).Port),
			AllowedKeys:   []key.NodePublic{device.Public()},
		}, updates, out)
	})
	address := hostEvent["address"]
	if hostEvent["event"] != "ready" || address == "" {
		t.Fatal("host did not return a tunnel address")
	}
	unknown := &tailcat.Client{Server: tailcat.Addr(address), Key: key.NewNode(), Logf: quietLog}
	attempt, stop := context.WithTimeout(ctx, 2*time.Second)
	_, err = unknown.Ping(attempt)
	stop()
	unknown.Close()
	if err == nil {
		t.Fatal("the tunnel admitted an unknown device")
	}

	clientEvent, clientDone := startTestHelper(t, func(out *json.Encoder) error {
		return join(ctx, joinConfig{Address: tailcat.Addr(address), Key: device}, out)
	})
	conn, err := (&net.Dialer{}).DialContext(ctx, "tcp4", clientEvent["local_address"])
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(10 * time.Second))
	if _, err := io.WriteString(conn, "Lectern"); err != nil {
		t.Fatal(err)
	}
	data := make([]byte, len("Lectern"))
	if _, err := io.ReadFull(conn, data); err != nil || string(data) != "Lectern" {
		t.Fatalf("gameplay round trip failed: %v", err)
	}

	select {
	case updates <- []byte(`{"allowed_keys":[]}`):
	case <-ctx.Done():
		t.Fatal("host did not accept the revocation update")
	}
	conn.SetReadDeadline(time.Now().Add(4 * time.Second))
	if _, err := conn.Read(data); err != io.EOF {
		t.Fatalf("revoked gameplay connection did not close: %v", err)
	}
	cancel()
	for _, done := range []<-chan error{hostDone, clientDone} {
		select {
		case err := <-done:
			if err != nil {
				t.Fatal(err)
			}
		case <-time.After(3 * time.Second):
			t.Fatal("helper did not exit")
		}
	}
}

func startTestHelper(t *testing.T, start func(*json.Encoder) error) (map[string]string, <-chan error) {
	t.Helper()
	reader, writer := io.Pipe()
	t.Cleanup(func() { reader.Close() })
	done := make(chan error, 1)
	events := make(chan map[string]string, 1)
	go func() {
		err := start(json.NewEncoder(writer))
		if err != nil {
			writer.CloseWithError(err)
		} else {
			writer.Close()
		}
		done <- err
	}()
	go func() {
		decoder := json.NewDecoder(reader)
		for {
			var event map[string]string
			if decoder.Decode(&event) != nil {
				return
			}
			if event["event"] == "ready" {
				events <- event
			}
		}
	}()
	select {
	case event := <-events:
		return event, done
	case err := <-done:
		t.Fatalf("helper failed before it was ready: %v", err)
	case <-time.After(35 * time.Second):
		t.Fatal("helper did not become ready")
	}
	return nil, nil
}
