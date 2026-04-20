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
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Core sync logic: compares local mods against the server manifest,
 * downloads new/updated mods, and removes mods that are no longer on the server.
 *
 * Tracks which files it manages via a state file (.lectern/managed-mods) in the
 * instance directory to avoid touching mods the player installed manually.
 */
public class ModSyncer {

    private static final String STATE_DIR = ".lectern";
    private static final String STATE_FILE = "managed-mods";
    private static final int DOWNLOAD_TIMEOUT_MS = 30000;
    private static final int DOWNLOAD_CONNECT_TIMEOUT_MS = 10000;

    private final File modsDir;
    private final File instanceDir;
    private final SyncConfig config;

    public ModSyncer(File modsDir, File instanceDir, SyncConfig config) {
        this.modsDir = modsDir;
        this.instanceDir = instanceDir;
        this.config = config;
    }

    /**
     * Sync local mods to match the server manifest.
     *
     * @param manifest The server manifest to sync against
     * @param ui       The sync dialog for visual feedback (may be null)
     */
    public SyncResult sync(ServerManifest manifest, SyncDialog ui) throws IOException {
        Set<String> managedFiles = loadState();
        Set<String> newManagedFiles = new HashSet<String>();
        int downloaded = 0;
        int removed = 0;
        int failed = 0;
        List<String> failedNames = new ArrayList<String>();

        // Build set of expected file names from manifest
        Set<String> expectedFiles = new HashSet<String>();
        for (ServerManifest.ModEntry mod : manifest.getMods()) {
            expectedFiles.add(mod.getFileName());
        }

        // Count how many need downloading
        int totalToDownload = 0;
        for (ServerManifest.ModEntry mod : manifest.getMods()) {
            File target = new File(modsDir, mod.getFileName());
            if (!target.exists() || !isFileValid(target, mod)) {
                totalToDownload++;
            }
        }

        int downloadIndex = 0;

        // Download new or updated mods
        for (ServerManifest.ModEntry mod : manifest.getMods()) {
            File target = new File(modsDir, mod.getFileName());
            newManagedFiles.add(mod.getFileName());

            if (target.exists() && isFileValid(target, mod)) {
                // File exists and matches — skip
                continue;
            }

            downloadIndex++;

            // Need to download
            String progressMsg = "Downloading (" + downloadIndex + "/" + totalToDownload + "): " + mod.getName();
            System.out.println("[Lectern]   " + progressMsg);
            if (ui != null) {
                ui.setStatus("Downloading mods... (" + downloadIndex + "/" + totalToDownload + ")");
                ui.setDetail(mod.getName());
                ui.log("  Downloading: " + mod.getName());
            }

            try {
                downloadFile(mod.getDownloadUrl(), target);
                downloaded++;
            } catch (IOException e) {
                System.err.println("[Lectern]   Failed to download " + mod.getName() + ": " + e.getMessage());
                failed++;
                failedNames.add(mod.getName());
                if (ui != null) {
                    ui.log("  FAILED: " + mod.getName() + " - " + e.getMessage());
                }
                // Keep existing file if download failed
                if (target.exists()) {
                    newManagedFiles.add(mod.getFileName());
                }
            }
        }

        // Remove mods that we previously managed but are no longer in the manifest
        for (String managedFile : managedFiles) {
            if (!expectedFiles.contains(managedFile)) {
                File old = new File(modsDir, managedFile);
                if (old.exists()) {
                    System.out.println("[Lectern]   Removing: " + managedFile);
                    if (ui != null) {
                        ui.log("  Removing: " + managedFile);
                    }
                    if (old.delete()) {
                        removed++;
                    } else {
                        System.err.println("[Lectern]   Could not delete: " + managedFile);
                        if (ui != null) {
                            ui.log("  Could not delete: " + managedFile);
                        }
                    }
                }
            }
        }

        // Save updated state
        saveState(newManagedFiles);

        return new SyncResult(downloaded, removed, failed, failedNames);
    }

    /**
     * Check if an existing file matches the manifest entry.
     * Uses SHA-1 hash if available, otherwise just checks existence.
     */
    private boolean isFileValid(File file, ServerManifest.ModEntry mod) {
        String expectedHash = mod.getSha1();
        if (expectedHash == null || expectedHash.isEmpty()) {
            // No hash to check — if file exists with the right name, assume it's fine
            return true;
        }

        try {
            String actualHash = sha1(file);
            return expectedHash.equalsIgnoreCase(actualHash);
        } catch (Exception e) {
            // Can't compute hash — re-download to be safe
            return false;
        }
    }

    /**
     * Download a file from a URL, following redirects.
     */
    private void downloadFile(String urlStr, File target) throws IOException {
        HttpURLConnection conn = null;
        InputStream in = null;
        FileOutputStream out = null;

        try {
            // Follow redirects (CDNs like Modrinth use them)
            int redirects = 0;
            while (redirects < 5) {
                conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "LecternSync/1.0");
                conn.setConnectTimeout(DOWNLOAD_CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(DOWNLOAD_TIMEOUT_MS);
                conn.setInstanceFollowRedirects(false);

                int status = conn.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = conn.getHeaderField("Location");
                    conn.disconnect();
                    if (location == null) {
                        throw new IOException("Redirect with no Location header");
                    }
                    urlStr = location;
                    redirects++;
                    continue;
                }

                if (status != 200) {
                    throw new IOException("HTTP " + status + " downloading " + target.getName());
                }
                break;
            }

            if (conn == null) {
                throw new IOException("Too many redirects");
            }

            // Download to a temp file first, then rename (atomic-ish)
            File temp = new File(modsDir, target.getName() + ".lectern-tmp");
            in = conn.getInputStream();
            out = new FileOutputStream(temp);

            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            out.close();
            out = null;

            // Replace target
            if (target.exists()) {
                target.delete();
            }
            if (!temp.renameTo(target)) {
                throw new IOException("Could not rename temp file to " + target.getName());
            }
        } finally {
            if (in != null) {
                try { in.close(); } catch (IOException ignored) {}
            }
            if (out != null) {
                try { out.close(); } catch (IOException ignored) {}
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * Compute SHA-1 hex digest of a file.
     */
    private String sha1(File file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(file);
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) != -1) {
                digest.update(buf, 0, n);
            }
        } finally {
            if (fis != null) {
                fis.close();
            }
        }

        byte[] hash = digest.digest();
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) {
            hex.append(String.format("%02x", b & 0xff));
        }
        return hex.toString();
    }

    /**
     * Load the set of filenames that we previously managed.
     * The state file is a simple newline-delimited list of filenames.
     */
    private File getStateFile() {
        File stateDir = new File(instanceDir, STATE_DIR);
        if (!stateDir.exists()) {
            stateDir.mkdirs();
        }
        return new File(stateDir, STATE_FILE);
    }

    private Set<String> loadState() {
        Set<String> files = new HashSet<String>();
        File stateFile = getStateFile();
        if (!stateFile.exists()) {
            // Migrate from old location (mods/.lectern-mods) if it exists
            File oldState = new File(modsDir, ".lectern-mods");
            if (oldState.exists()) {
                oldState.renameTo(stateFile);
            } else {
                return files;
            }
        }

        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(stateFile), Charset.forName("UTF-8")));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) {
                    files.add(line);
                }
            }
        } catch (IOException e) {
            // If we can't read state, start fresh
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (IOException ignored) {}
            }
        }

        return files;
    }

    /**
     * Save the set of filenames we now manage.
     */
    private void saveState(Set<String> files) throws IOException {
        File stateFile = getStateFile();
        BufferedWriter writer = null;
        try {
            writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(stateFile), Charset.forName("UTF-8")));
            for (String fileName : files) {
                writer.write(fileName);
                writer.newLine();
            }
        } finally {
            if (writer != null) {
                writer.close();
            }
        }
    }

    /**
     * Result of a sync operation.
     */
    public static class SyncResult {
        private final int downloaded;
        private final int removed;
        private final int failed;
        private final List<String> failedNames;

        public SyncResult(int downloaded, int removed, int failed, List<String> failedNames) {
            this.downloaded = downloaded;
            this.removed = removed;
            this.failed = failed;
            this.failedNames = failedNames;
        }

        public int getDownloaded() {
            return downloaded;
        }

        public int getRemoved() {
            return removed;
        }

        public int getFailed() {
            return failed;
        }

        public List<String> getFailedNames() {
            return failedNames;
        }
    }
}
