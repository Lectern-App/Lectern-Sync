package dev.lectern.sync.updater;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.MessageDigest;

/**
 * Lectern Sync Updater — tiny bootstrap that keeps lectern-sync.jar up to date.
 *
 * Flow:
 *   1. Find instance directory (lectern.json)
 *   2. Read relay_url from lectern.json
 *   3. GET {relay}/static/lectern-sync.jar/version → expected SHA-256
 *   4. Compare with local lectern-sync.jar SHA-256
 *   5. If different: download new jar (with hash verification)
 *   6. Launch: java -jar lectern-sync.jar (inherit IO, wait for exit)
 *   7. Exit with sync tool's exit code
 *
 * Designed to never prevent the game from launching:
 *   - Relay unreachable → use existing jar
 *   - Download fails → use existing jar
 *   - No existing jar AND no network → print error, exit 0
 *
 * Zero dependencies, Java 8, ~120 lines. Intentionally small so it never needs
 * updating itself (and if it does, the player re-downloads their instance).
 */
public class SyncUpdater {

    private static final String VERSION = "1.0.0";
    private static final String CONFIG_FILE = "lectern.json";
    private static final String SYNC_JAR = "lectern-sync.jar";
    private static final String DEFAULT_RELAY_URL = "https://relay.thelectern.app";
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 30000;

    public static void main(String[] args) {
        System.out.println("[Lectern Updater v" + VERSION + "]");

        File instanceDir = findInstanceDir();
        if (instanceDir == null) {
            System.out.println("[Lectern Updater] No " + CONFIG_FILE + " found. Skipping update check.");
            launchSyncJar(new File(SYNC_JAR));
            return;
        }

        File syncJar = new File(instanceDir, SYNC_JAR);
        String relayUrl = readRelayUrl(new File(instanceDir, CONFIG_FILE));
        if (relayUrl == null || relayUrl.isEmpty()) {
            relayUrl = DEFAULT_RELAY_URL;
        }

        // Try to check for updates — any failure falls through to launching the existing jar
        try {
            String expectedSha = fetchExpectedSha256(relayUrl);
            if (expectedSha == null) {
                System.out.println("[Lectern Updater] Relay has no version info; skipping update.");
            } else {
                String localSha = syncJar.exists() ? sha256OfFile(syncJar) : null;
                if (localSha != null && localSha.equalsIgnoreCase(expectedSha)) {
                    System.out.println("[Lectern Updater] Sync jar is up to date.");
                } else {
                    if (localSha == null) {
                        System.out.println("[Lectern Updater] Sync jar missing. Downloading...");
                    } else {
                        System.out.println("[Lectern Updater] Update available. Downloading...");
                    }
                    downloadSyncJar(relayUrl, syncJar, expectedSha);
                    System.out.println("[Lectern Updater] Sync jar updated.");
                }
            }
        } catch (IOException e) {
            System.out.println("[Lectern Updater] Update check failed: " + e.getMessage());
            System.out.println("[Lectern Updater] Launching existing sync jar.");
        } catch (Exception e) {
            System.out.println("[Lectern Updater] Unexpected error during update: " + e.getMessage());
        }

        launchSyncJar(syncJar);
    }

    /** Launch java -jar lectern-sync.jar, inherit stdio, exit with its code. */
    private static void launchSyncJar(File syncJar) {
        if (!syncJar.exists()) {
            System.err.println("[Lectern Updater] " + SYNC_JAR + " not found and could not be downloaded.");
            System.err.println("[Lectern Updater] Launching game without sync.");
            System.exit(0);
            return;
        }

        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        ProcessBuilder pb = new ProcessBuilder(javaBin, "-jar", syncJar.getAbsolutePath());
        pb.directory(syncJar.getParentFile());
        pb.inheritIO();
        try {
            int code = pb.start().waitFor();
            System.exit(code);
        } catch (IOException e) {
            System.err.println("[Lectern Updater] Failed to launch sync jar: " + e.getMessage());
            System.exit(0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.exit(0);
        }
    }

    /** Look for lectern.json in current or parent dir (matches LecternSync.findInstanceDir). */
    private static File findInstanceDir() {
        File current = new File(System.getProperty("user.dir"));
        if (new File(current, CONFIG_FILE).exists()) {
            return current;
        }
        File parent = current.getParentFile();
        if (parent != null && new File(parent, CONFIG_FILE).exists()) {
            return parent;
        }
        return null;
    }

    /** Minimal JSON scrape for "relay_url" — avoids pulling in a JSON library. */
    private static String readRelayUrl(File configFile) {
        try {
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(configFile), Charset.forName("UTF-8")));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line);
                }
            } finally {
                r.close();
            }
            return extractJsonString(sb.toString(), "relay_url");
        } catch (IOException e) {
            return null;
        }
    }

    /** Extract string value for a top-level key from a flat JSON object. */
    private static String extractJsonString(String json, String key) {
        String needle = "\"" + key + "\"";
        int k = json.indexOf(needle);
        if (k < 0) return null;
        int colon = json.indexOf(':', k + needle.length());
        if (colon < 0) return null;
        int quote = json.indexOf('"', colon + 1);
        if (quote < 0) return null;
        int end = json.indexOf('"', quote + 1);
        if (end < 0) return null;
        return json.substring(quote + 1, end);
    }

    /** GET {relay}/static/lectern-sync.jar/version — expects {"sha256": "..."}. */
    private static String fetchExpectedSha256(String relayUrl) throws IOException {
        HttpURLConnection conn = openGet(relayUrl + "/static/lectern-sync.jar/version");
        int code = conn.getResponseCode();
        if (code == 404) {
            // Relay hasn't been set up for version checks yet — skip update.
            return null;
        }
        if (code != 200) {
            throw new IOException("Version endpoint returned HTTP " + code);
        }
        InputStream in = conn.getInputStream();
        StringBuilder sb = new StringBuilder();
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, Charset.forName("UTF-8")));
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line);
            }
        } finally {
            in.close();
        }
        return extractJsonString(sb.toString(), "sha256");
    }

    /** Download sync jar to .tmp, verify hash, then rename. */
    private static void downloadSyncJar(String relayUrl, File target, String expectedSha) throws IOException {
        HttpURLConnection conn = openGet(relayUrl + "/static/lectern-sync.jar");
        int code = conn.getResponseCode();
        if (code != 200) {
            throw new IOException("Download returned HTTP " + code);
        }

        File tmp = new File(target.getAbsolutePath() + ".tmp");
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IOException("SHA-256 not available", e);
        }

        InputStream in = conn.getInputStream();
        OutputStream out = new FileOutputStream(tmp);
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                sha.update(buf, 0, n);
            }
        } finally {
            try { out.close(); } catch (IOException ignored) {}
            try { in.close(); } catch (IOException ignored) {}
        }

        String actual = toHex(sha.digest());
        if (expectedSha != null && !actual.equalsIgnoreCase(expectedSha)) {
            tmp.delete();
            throw new IOException("SHA-256 mismatch (expected " + expectedSha + ", got " + actual + ")");
        }

        // Replace atomically: delete old, rename tmp.
        if (target.exists() && !target.delete()) {
            tmp.delete();
            throw new IOException("Could not delete old sync jar");
        }
        if (!tmp.renameTo(target)) {
            throw new IOException("Could not rename sync jar into place");
        }
    }

    private static HttpURLConnection openGet(String urlStr) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "lectern-sync-updater/" + VERSION);
        return conn;
    }

    private static String sha256OfFile(File file) throws IOException {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IOException("SHA-256 not available", e);
        }
        InputStream in = new FileInputStream(file);
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                sha.update(buf, 0, n);
            }
        } finally {
            in.close();
        }
        return toHex(sha.digest());
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }
}
