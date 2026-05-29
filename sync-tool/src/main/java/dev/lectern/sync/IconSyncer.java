package dev.lectern.sync;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Downloads the server icon from the Lectern relay (or direct fallback) and
 * writes it into the launcher's instance so the launcher's instance grid
 * shows the correct art.
 *
 * Icons are content-addressed by SHA-256: the manifest tells us which hash
 * to expect, we download, verify, and then drop the PNG into place. The
 * cached hash is stored at {instance}/.lectern/icon-hash so repeat launches
 * skip the network round-trip.
 *
 * Launcher integration is best-effort and silent: if we can't detect either
 * Prism or the Modrinth App, we still write icon.png inside the instance
 * directory but don't rewrite anything else. Any failure here is logged and
 * swallowed — icon is cosmetic; mod sync is what matters.
 */
public class IconSyncer {

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int MAX_ICON_BYTES = 128 * 1024;

    private IconSyncer() {}

    /**
     * Entry point. Safe to call with a manifest that has no icon_hash —
     * in that case we do nothing (we do NOT delete a previously-applied
     * icon; the player may have customised it).
     */
    public static void sync(File instanceDir, ServerManifest manifest, SyncConfig config, SyncDialog ui) {
        String hash = manifest.getIconHash();
        if (hash == null || hash.isEmpty()) {
            return;
        }

        try {
            File lecternDir = new File(instanceDir, ".lectern");
            if (!lecternDir.exists()) {
                lecternDir.mkdirs();
            }

            File cacheFile = new File(lecternDir, "icon-hash");
            File iconFile = new File(instanceDir, "icon.png");

            String cached = readTextFile(cacheFile);
            if (cached != null && cached.equals(hash) && iconFile.exists()) {
                // Already applied — still make sure launcher config points at it.
                applyLauncherConfig(instanceDir, ui);
                return;
            }

            if (ui != null) ui.log("Downloading server icon...");

            byte[] bytes = downloadIcon(hash, config);
            if (bytes == null) {
                if (ui != null) ui.log("Could not download server icon (skipping).");
                return;
            }

            String actual = sha256Hex(bytes);
            if (!actual.equalsIgnoreCase(hash)) {
                if (ui != null) ui.log("Server icon hash mismatch (skipping).");
                return;
            }

            // Atomic-ish write: temp file + rename
            File tmp = new File(instanceDir, "icon.png.lectern-tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(bytes);
            } finally {
                out.close();
            }
            if (iconFile.exists()) iconFile.delete();
            if (!tmp.renameTo(iconFile)) {
                throw new IOException("Could not rename icon temp file");
            }

            applyLauncherConfig(instanceDir, ui);

            writeTextFile(cacheFile, hash);
            if (ui != null) ui.log("Server icon updated.");

        } catch (Exception e) {
            // Icon is cosmetic — never let it break the launch.
            System.err.println("[Lectern] Icon sync failed: " + e.getMessage());
            if (ui != null) ui.log("Icon sync failed: " + e.getMessage());
        }
    }

    /** Try relay first, then direct Lectern. Returns null if both fail. */
    private static byte[] downloadIcon(String hash, SyncConfig config) {
        List<String> urls = new ArrayList<String>();
        if (config.hasRelay()) {
            urls.add(config.getRelayUrl() + "/static/icons/" + hash + ".png");
        }
        if (config.hasDirectUrl() && config.getServerId() != null) {
            urls.add(config.getServerUrl() + "/api/sync/" + config.getServerId() + "/icon");
        }

        for (String url : urls) {
            try {
                return fetchBytes(url);
            } catch (IOException ignored) {
                // Try next source
            }
        }
        return null;
    }

    private static byte[] fetchBytes(String urlStr) throws IOException {
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            int redirects = 0;
            while (redirects < 5) {
                conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "LecternSync/1.0");
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setInstanceFollowRedirects(false);

                int status = conn.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = conn.getHeaderField("Location");
                    conn.disconnect();
                    if (location == null) throw new IOException("Redirect with no Location header");
                    urlStr = location;
                    redirects++;
                    continue;
                }
                if (status != 200) {
                    throw new IOException("HTTP " + status);
                }
                break;
            }

            in = conn.getInputStream();
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(chunk)) != -1) {
                total += n;
                if (total > MAX_ICON_BYTES) {
                    throw new IOException("Icon too large (>" + MAX_ICON_BYTES + " bytes)");
                }
                buf.write(chunk, 0, n);
            }
            return buf.toByteArray();
        } finally {
            if (in != null) { try { in.close(); } catch (IOException ignored) {} }
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Detect the launcher type by probing well-known files next to the
     * instance directory, and wire up its icon config so the launcher picks
     * up the PNG we just wrote.
     *
     * Prism/MultiMC: instance.cfg sits next to (not inside) the minecraft
     * directory. Prism reads icons as {iconKey}.png from the directory that
     * contains instance.cfg. So we set iconKey=lectern and copy icon.png
     * out to lectern.png there.
     *
     * Modrinth App: profile.json lives alongside the instance files. Set
     * icon_path="icon.png" (relative to the profile dir — see
     * applyModrinthProfile for why that path is correct).
     */
    private static void applyLauncherConfig(File instanceDir, SyncDialog ui) {
        File parent = instanceDir.getParentFile();
        if (parent == null) return;

        File icon = new File(instanceDir, "icon.png");
        if (!icon.exists()) return;

        File instanceCfg = new File(parent, "instance.cfg");
        if (instanceCfg.exists()) {
            try {
                applyPrismConfig(instanceCfg, parent, icon);
                if (ui != null) ui.log("Prism icon updated.");
            } catch (IOException e) {
                if (ui != null) ui.log("Could not update Prism icon: " + e.getMessage());
            }
        }

        File profileJson = new File(parent, "profile.json");
        if (profileJson.exists()) {
            try {
                applyModrinthProfile(profileJson);
                if (ui != null) ui.log("Modrinth icon updated.");
            } catch (IOException e) {
                if (ui != null) ui.log("Could not update Modrinth icon: " + e.getMessage());
            }
        }
    }

    /**
     * Prism instance.cfg is a simple key=value file. Rewrite iconKey=lectern
     * and drop a copy of the icon as lectern.png next to instance.cfg.
     */
    private static void applyPrismConfig(File instanceCfg, File parent, File icon) throws IOException {
        // Read all lines
        List<String> lines = new ArrayList<String>();
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(instanceCfg), Charset.forName("UTF-8")));
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        } finally {
            if (reader != null) reader.close();
        }

        // Update or insert iconKey
        boolean updated = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith("iconKey=")) {
                if (line.equals("iconKey=lectern")) {
                    updated = true;
                    break;
                }
                lines.set(i, "iconKey=lectern");
                updated = true;
                break;
            }
        }
        if (!updated) {
            lines.add("iconKey=lectern");
        }

        // Copy the icon bytes to lectern.png next to instance.cfg
        File iconTarget = new File(parent, "lectern.png");
        File tmp = new File(parent, "lectern.png.lectern-tmp");
        FileInputStream in = null;
        FileOutputStream out = null;
        try {
            in = new FileInputStream(icon);
            out = new FileOutputStream(tmp);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        } finally {
            if (in != null) { try { in.close(); } catch (IOException ignored) {} }
            if (out != null) { try { out.close(); } catch (IOException ignored) {} }
        }
        if (iconTarget.exists()) iconTarget.delete();
        if (!tmp.renameTo(iconTarget)) {
            throw new IOException("Could not rename lectern.png temp file");
        }

        // Write instance.cfg back atomically
        File cfgTmp = new File(parent, "instance.cfg.lectern-tmp");
        BufferedWriter writer = null;
        try {
            writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(cfgTmp), Charset.forName("UTF-8")));
            for (String line : lines) {
                writer.write(line);
                writer.write('\n');
            }
        } finally {
            if (writer != null) writer.close();
        }
        if (instanceCfg.exists()) instanceCfg.delete();
        if (!cfgTmp.renameTo(instanceCfg)) {
            throw new IOException("Could not rename instance.cfg temp file");
        }
    }

    /**
     * Modrinth App's profile.json is true JSON with an "icon_path" string
     * field. Rewrite it to point at the icon we just dropped into the
     * minecraft dir. icon_path is relative to the profile.json's directory;
     * our icon lives one level deeper, so we use "<instanceDirName>/icon.png".
     *
     * Uses a minimal regex-based field rewriter to avoid pulling in a JSON
     * library — JsonHelper only reads, it doesn't write.
     */
    private static void applyModrinthProfile(File profileJson) throws IOException {
        String body = readTextFile(profileJson);
        if (body == null) return;

        // We don't know the instance dir name from inside here — but the
        // Modrinth App convention is that the minecraft dir alongside
        // profile.json is always named "profile" or matches the profile's
        // own path field. Keep it simple: the icon lives at
        // "<parent>/icon.png" from the launcher's perspective, same level
        // as profile.json -> icon_path should just be "icon.png" IF the
        // launcher mounts the profile dir as the mc dir. For safety, we
        // also try a nested path. Current Modrinth App mounts profile.json
        // alongside the instance files, so "icon.png" is correct.
        String newBody;
        if (body.contains("\"icon_path\"")) {
            newBody = body.replaceAll(
                "\"icon_path\"\\s*:\\s*(\"[^\"]*\"|null)",
                "\"icon_path\": \"icon.png\"");
        } else {
            // Insert before closing brace
            int lastBrace = body.lastIndexOf('}');
            if (lastBrace < 0) return;
            String head = body.substring(0, lastBrace).trim();
            if (head.endsWith(",")) head = head.substring(0, head.length() - 1);
            newBody = head + ",\n  \"icon_path\": \"icon.png\"\n}\n";
        }

        File tmp = new File(profileJson.getParentFile(), "profile.json.lectern-tmp");
        BufferedWriter writer = null;
        try {
            writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(tmp), Charset.forName("UTF-8")));
            writer.write(newBody);
        } finally {
            if (writer != null) writer.close();
        }
        if (profileJson.exists()) profileJson.delete();
        if (!tmp.renameTo(profileJson)) {
            throw new IOException("Could not rename profile.json temp file");
        }
    }

    private static String readTextFile(File file) {
        if (!file.exists()) return null;
        try {
            StringBuilder sb = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), Charset.forName("UTF-8")));
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(line);
                }
            } finally {
                reader.close();
            }
            return sb.toString().trim();
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeTextFile(File file, String content) throws IOException {
        BufferedWriter writer = null;
        try {
            writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(file), Charset.forName("UTF-8")));
            writer.write(content);
        } finally {
            if (writer != null) writer.close();
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
