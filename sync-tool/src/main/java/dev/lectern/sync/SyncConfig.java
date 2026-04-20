package dev.lectern.sync;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;

/**
 * Reads and writes the lectern.json config file bundled in the modpack.
 *
 * Expected format:
 * {
 *   "server_url": "http://localhost:8420",
 *   "server_id": "uuid-here",
 *   "server_name": "My Server",
 *   "relay_url": "https://relay.thelectern.app"
 * }
 *
 * The relay_url is the primary source for manifests. server_url is the
 * direct fallback if the relay is unreachable. Either or both may be present.
 */
public class SyncConfig {

    private static final String DEFAULT_RELAY_URL = "https://relay.thelectern.app";

    private String serverUrl;
    private final String serverId;
    private String serverName;
    private String relayUrl;
    private File configFile;

    public SyncConfig(String serverUrl, String serverId, String serverName, String relayUrl) {
        this.serverUrl = serverUrl;
        this.serverId = serverId;
        this.serverName = serverName;
        this.relayUrl = relayUrl;
    }

    public String getServerUrl() {
        return serverUrl;
    }

    public String getServerId() {
        return serverId;
    }

    public String getServerName() {
        return serverName;
    }

    public String getRelayUrl() {
        return relayUrl;
    }

    /**
     * Whether a relay URL is available for fetching manifests.
     */
    public boolean hasRelay() {
        return relayUrl != null && !relayUrl.isEmpty();
    }

    /**
     * Whether a direct server URL is available as fallback.
     */
    public boolean hasDirectUrl() {
        return serverUrl != null && !serverUrl.isEmpty();
    }

    public static SyncConfig load(File file) throws IOException {
        String json = readFile(file);
        String serverUrl = JsonHelper.getString(json, "server_url");
        String serverId = JsonHelper.getString(json, "server_id");
        String serverName = JsonHelper.getString(json, "server_name");
        String relayUrl = JsonHelper.getString(json, "relay_url");

        if (serverId == null || serverId.isEmpty()) {
            throw new IOException("Missing 'server_id' in lectern.json");
        }

        // At least one source must be available
        boolean hasRelay = relayUrl != null && !relayUrl.isEmpty();
        boolean hasDirect = serverUrl != null && !serverUrl.isEmpty();
        if (!hasRelay && !hasDirect) {
            throw new IOException("lectern.json must contain 'relay_url' or 'server_url' (or both)");
        }

        if (serverName == null) {
            serverName = "Unknown Server";
        }

        // Remove trailing slashes
        if (serverUrl != null && serverUrl.endsWith("/")) {
            serverUrl = serverUrl.substring(0, serverUrl.length() - 1);
        }
        if (relayUrl != null && relayUrl.endsWith("/")) {
            relayUrl = relayUrl.substring(0, relayUrl.length() - 1);
        }

        // Default relay URL if not specified but direct URL is present
        if (!hasRelay && hasDirect) {
            relayUrl = DEFAULT_RELAY_URL;
        }

        SyncConfig config = new SyncConfig(serverUrl, serverId, serverName, relayUrl);
        config.configFile = file;
        return config;
    }

    /**
     * Save the config back to disk (e.g. after relay populates missing fields).
     * Only writes if we know the file path.
     */
    public void save() throws IOException {
        if (configFile == null) return;

        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        if (serverUrl != null && !serverUrl.isEmpty()) {
            sb.append("  \"server_url\": \"").append(escapeJson(serverUrl)).append("\",\n");
        }
        sb.append("  \"server_id\": \"").append(escapeJson(serverId)).append("\",\n");
        sb.append("  \"server_name\": \"").append(escapeJson(serverName)).append("\",\n");
        if (relayUrl != null && !relayUrl.isEmpty()) {
            sb.append("  \"relay_url\": \"").append(escapeJson(relayUrl)).append("\"\n");
        }
        sb.append("}\n");

        BufferedWriter writer = null;
        try {
            writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(configFile), Charset.forName("UTF-8")));
            writer.write(sb.toString());
        } finally {
            if (writer != null) {
                writer.close();
            }
        }
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String readFile(File file) throws IOException {
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), Charset.forName("UTF-8")));
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } finally {
            if (reader != null) {
                reader.close();
            }
        }
        return sb.toString();
    }
}
