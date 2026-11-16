# Lectern Connect gameplay helper

Build and run the local checks with Go 1.27.2 or newer.

```sh
go build -o lectern-connect .
go test ./...
```

Set `LECTERN_CONNECT_LIVE_TEST=1` when running `go test -run TestPublicDERPGameplay -v`
to test real Tailcat rendezvous on the public DERP map. The test uses a local
TCP fixture and needs no Minecraft installation or account.

The helper has three modes. Host and join read one JSON message per line on
stdin, emit JSON events on stdout, and stop when the parent closes stdin.

| Mode | Input | Output |
| --- | --- | --- |
| `keygen` | None | A new `private_key` and `public_key` for the player device |
| `host` | `minecraft_port`, `allowed_keys`, optional `derp_map_url` | A `ready` event with a Tailcat `address` |
| `join` | `address`, `private_key`, optional `local_port` | A `ready` event with a loopback `local_address` for Minecraft |

Send `{"allowed_keys":[…]}` to a running host to replace its access list. An
empty list denies every device. The host rejects unknown device keys and
allows up to ten simultaneous forwarded TCP connections. The only destination
is the host's configured Minecraft port on IPv4 loopback. Minecraft still
handles game-account authentication and its own allowlist.

The player keeps the private key. The host receives only approved public
keys. The host's ready event contains secret connection details, which should
only be handed to authorized players. Keep private keys and tunnel addresses
out of command-line arguments and logs.

Without an override, the host uses Tailcat's public DERP map. For a private
DERP region, the host supervisor can pass its HTTPS map URL when starting a
new session. The address issued by the host includes the selected relay
region, so the joining helper gets the new route without a binary update.
Public Tailcat capacity is best effort.

## Lectern and Prism gameplay setup

Place the helper beside the Lectern host executable, or set
`LECTERN_CONNECT_BIN` to its absolute path. Select **Lectern Connect** in the
server's **Connection** tab, save, and start Minecraft. The host starts the
tunnel after Minecraft is ready. Set `LECTERN_CONNECT_DERP_MAP_URL` in the
Lectern process environment when a private relay map is available.

Build the player JAR from `sync-tool` with
`gradle build -PconnectBinaries=/absolute/path/to/binaries`. The binary
directory should contain `windows-amd64/lectern-connect.exe`,
`linux-amd64/lectern-connect`, or the corresponding OS and architecture folders
for the launchers being tested.

1. On the player's machine, run
   `java -jar lectern-sync.jar connect-keygen "/path/to/instance/.minecraft"`.
   Send the printed public key to the host and add it in **Connection**.
   The private key lives in the player's home directory outside the instance.
2. Copy the private Connect address from **Connection** to the player's
   `.minecraft/.lectern/connect.json` as `{"address":"tc…"}`.
   Repeat this step when a new host session changes the address.
3. Place the JAR in the instance folder and set Prism's **Wrapper command**
   using absolute paths:

   ```text
   "/path/to/java" -jar "/path/to/instance/lectern-sync.jar" connect "/path/to/instance/.minecraft"
   ```

   Launch and select
   **Lectern Connect** from the Minecraft multiplayer list.

This setup pairs devices through the admin controls. The public Relay page
does not issue tunnel addresses.
