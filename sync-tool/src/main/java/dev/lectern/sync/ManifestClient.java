package dev.lectern.sync;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;

/**
 * HTTP client for fetching the server manifest.
 *
 * Fetching order:
 *   1. Relay (primary) — always works if the relay is up, no direct server access needed
 *   2. Direct URL (fallback) — if the relay is down, try the Lectern server directly
 *   3. Both fail — throw IOException so the caller can decide what to do
 *
 * Uses HttpURLConnection (available since Java 1.1) — no external dependencies.
 */
public class ManifestClient {

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 10000;

    /** Which source the manifest was fetched from. */
    public enum Source {
        RELAY, DIRECT
    }

    /** Result of a manifest fetch, including which source succeeded. */
    public static class FetchResult {
        private final ServerManifest manifest;
        private final Source source;

        public FetchResult(ServerManifest manifest, Source source) {
            this.manifest = manifest;
            this.source = source;
        }

        public ServerManifest getManifest() { return manifest; }
        public Source getSource() { return source; }
    }

    /**
     * Fetch the mod manifest using the relay-first fallback chain.
     *
     * @param config The sync config containing relay_url and/or server_url
     * @return FetchResult with the manifest and which source it came from
     * @throws IOException if all sources fail
     */
    public static FetchResult fetchManifest(SyncConfig config) throws IOException {
        String serverId = config.getServerId();
        IOException relayError = null;
        IOException directError = null;
        int sourcesTried = 0;
        int sourcesNotFound = 0;

        // 1. Try relay (primary)
        if (config.hasRelay()) {
            sourcesTried++;
            String relayUrl = config.getRelayUrl() + "/s/" + serverId + "/manifest";
            try {
                ServerManifest manifest = doFetch(relayUrl);
                return new FetchResult(manifest, Source.RELAY);
            } catch (ManifestNotFoundException e) {
                relayError = e;
                sourcesNotFound++;
            } catch (IOException e) {
                relayError = e;
            }
        }

        // 2. Try direct URL (fallback)
        if (config.hasDirectUrl()) {
            sourcesTried++;
            String directUrl = config.getServerUrl() + "/api/sync/" + serverId + "/manifest";
            try {
                ServerManifest manifest = doFetch(directUrl);
                return new FetchResult(manifest, Source.DIRECT);
            } catch (ManifestNotFoundException e) {
                directError = e;
                sourcesNotFound++;
            } catch (IOException e) {
                directError = e;
            }
        }

        // 3. Every source we tried returned 404 → the server genuinely doesn't
        // exist on any of them (as opposed to one being unreachable).
        if (sourcesTried > 0 && sourcesNotFound == sourcesTried) {
            throw new ManifestNotFoundException(
                "Server " + serverId + " is not registered on any configured source.");
        }

        // 4. Mixed or all network failures — report them all.
        StringBuilder msg = new StringBuilder("Could not fetch manifest from any source.");
        if (relayError != null) {
            msg.append("\n  Relay: ").append(relayError.getMessage());
        }
        if (directError != null) {
            msg.append("\n  Direct: ").append(directError.getMessage());
        }
        if (sourcesTried == 0) {
            msg.append("\n  No relay_url or server_url configured.");
        }
        throw new IOException(msg.toString());
    }

    /**
     * Fetch the mod manifest from a specific URL (legacy method for backwards compatibility).
     *
     * @param serverUrl Base URL of the Lectern server (e.g. "http://localhost:8420")
     * @param serverId  UUID of the server
     * @return Parsed ServerManifest
     * @throws IOException if the request fails or the server is unreachable
     */
    public static ServerManifest fetchManifest(String serverUrl, String serverId) throws IOException {
        String url = serverUrl + "/api/sync/" + serverId + "/manifest";
        return doFetch(url);
    }

    /**
     * Fetch the game server address from the relay.
     *
     * @param relayUrl Base relay URL (e.g. "https://relay.thelectern.app")
     * @param serverId UUID of the server
     * @return The game server address (e.g. "abc.joinmc.link:25565"), or null if not set
     * @throws IOException if the request fails
     */
    public static String fetchServerAddress(String relayUrl, String serverId) throws IOException {
        String url = relayUrl + "/s/" + serverId + "/address";
        String json = doFetchRaw(url);
        String address = JsonHelper.getString(json, "address");
        if (address != null && address.equals("null")) {
            return null;
        }
        return address;
    }

    private static ServerManifest doFetch(String url) throws IOException {
        String json = doFetchRaw(url);
        return ServerManifest.parse(json);
    }

    private static String doFetchRaw(String url) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "LecternSync/1.0");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);

            int status = conn.getResponseCode();
            if (status != 200) {
                String msg = "HTTP " + status;
                try {
                    BufferedReader err = new BufferedReader(new InputStreamReader(
                        conn.getErrorStream(), Charset.forName("UTF-8")));
                    String line = err.readLine();
                    if (line != null && !line.isEmpty()) {
                        msg += ": " + line;
                    }
                    err.close();
                } catch (Exception ignored) {
                }
                // A 404 from the server means the request reached it fine —
                // the server just has no record of this ID. Surface it as a
                // distinct error so callers can differentiate "server gone"
                // from "relay unreachable".
                if (status == 404) {
                    throw new ManifestNotFoundException(msg);
                }
                throw new IOException(msg);
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(
                conn.getInputStream(), Charset.forName("UTF-8")));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            return sb.toString();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
