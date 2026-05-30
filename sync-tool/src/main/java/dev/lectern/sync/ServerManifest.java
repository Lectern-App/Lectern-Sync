package dev.lectern.sync;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents the server's mod manifest fetched from the Lectern API.
 *
 * JSON structure:
 * {
 *   "server_id": "uuid",
 *   "server_name": "My Server",
 *   "mc_version": "1.20.1",
 *   "mod_loader": "fabric",
 *   "loader_version": "0.14.24",
 *   "manifest_version": 3,
 *   "updated_at": "2024-...",
 *   "mods": [ { "name", "file_name", "download_url", "file_hash", "sha1", "sha512", "side", "source" } ]
 * }
 */
public class ServerManifest {

    private final long manifestVersion;
    private final List<ModEntry> mods;
    private final String serverName;
    private final String serverAddress;

    public ServerManifest(long manifestVersion, List<ModEntry> mods, String serverName, String serverAddress) {
        this.manifestVersion = manifestVersion;
        this.mods = mods;
        this.serverName = serverName;
        this.serverAddress = serverAddress;
    }

    public long getManifestVersion() {
        return manifestVersion;
    }

    public List<ModEntry> getMods() {
        return mods;
    }

    public String getServerName() {
        return serverName;
    }

    public String getServerAddress() {
        return serverAddress;
    }

    /**
     * Parse a ServerManifest from the JSON response body.
     */
    public static ServerManifest parse(String json) {
        long version = JsonHelper.getLong(json, "manifest_version", 0);
        String serverName = JsonHelper.getString(json, "server_name");
        String serverAddress = JsonHelper.getString(json, "server_address");
        List<String> modObjects = JsonHelper.getObjectArray(json, "mods");
        List<ModEntry> mods = new ArrayList<ModEntry>();

        for (String modJson : modObjects) {
            String name = JsonHelper.getString(modJson, "name");
            String fileName = JsonHelper.getString(modJson, "file_name");
            String downloadUrl = JsonHelper.getString(modJson, "download_url");
            String fileHash = JsonHelper.getString(modJson, "file_hash");
            String sha1 = JsonHelper.getString(modJson, "sha1");

            if (fileName != null && downloadUrl != null) {
                mods.add(new ModEntry(
                    name != null ? name : fileName,
                    fileName,
                    downloadUrl,
                    fileHash,
                    sha1
                ));
            }
        }

        return new ServerManifest(version, mods, serverName, serverAddress);
    }

    /**
     * A single mod entry from the manifest.
     */
    public static class ModEntry {
        private final String name;
        private final String fileName;
        private final String downloadUrl;
        private final String fileHash;
        private final String sha1;

        public ModEntry(String name, String fileName, String downloadUrl, String fileHash, String sha1) {
            this.name = name;
            this.fileName = fileName;
            this.downloadUrl = downloadUrl;
            this.fileHash = fileHash;
            this.sha1 = sha1;
        }

        public String getName() {
            return name;
        }

        public String getFileName() {
            return fileName;
        }

        public String getDownloadUrl() {
            return downloadUrl;
        }

        public String getFileHash() {
            return fileHash;
        }

        public String getSha1() {
            return sha1;
        }
    }
}
