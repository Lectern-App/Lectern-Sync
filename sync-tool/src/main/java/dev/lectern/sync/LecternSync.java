package dev.lectern.sync;

import java.io.File;
import java.io.IOException;

/**
 * Lectern Sync Tool — keeps a player's local mods in sync with a Lectern server.
 *
 * Designed to run as a pre-launch command in Minecraft launchers (e.g. Prism Launcher).
 * Reads lectern.json from the Minecraft instance directory, fetches the server's mod
 * manifest, and downloads/removes mods as needed.
 *
 * Manifest fetch order:
 *   1. Lectern Relay (primary) — relay.thelectern.app hosts all player-facing content
 *   2. Direct Lectern server (fallback) — if the relay is unreachable
 *   3. Both fail — launch anyway with current mods
 *
 * Shows a visual dialog with progress so the player knows what's happening.
 *
 * Exit codes:
 *   0 = success (mods are in sync, Minecraft should launch)
 *   0 = server unreachable (launch anyway with current mods)
 *   1 = fatal error (bad config, etc.)
 */
public class LecternSync {

    private static final String VERSION = "1.0.0";
    private static final String CONFIG_FILE = "lectern.json";

    public static void main(String[] args) {
        System.out.println("[Lectern Sync v" + VERSION + "]");

        // Find the Minecraft instance directory (where lectern.json lives)
        File instanceDir = findInstanceDir();
        if (instanceDir == null) {
            System.out.println("[Lectern] No " + CONFIG_FILE + " found. Skipping sync.");
            System.exit(0);
        }

        // Read config
        SyncConfig config;
        try {
            config = SyncConfig.load(new File(instanceDir, CONFIG_FILE));
        } catch (IOException e) {
            System.err.println("[Lectern] Failed to read " + CONFIG_FILE + ": " + e.getMessage());
            System.exit(1);
            return;
        }

        // Open the sync dialog
        SyncDialog ui = new SyncDialog(config.getServerName());
        ui.setStatus("Connecting...");
        ui.log("Lectern Sync v" + VERSION);
        ui.log("Server: " + config.getServerName());

        System.out.println("[Lectern] Server: " + config.getServerName());

        if (config.hasRelay()) {
            ui.setDetail("Fetching from relay...");
            System.out.println("[Lectern] Trying relay: " + config.getRelayUrl());
        } else if (config.hasDirectUrl()) {
            ui.setDetail("Connecting to " + config.getServerUrl());
            System.out.println("[Lectern] Trying direct: " + config.getServerUrl());
        }

        // Fetch manifest using relay-first fallback chain
        ManifestClient.FetchResult fetchResult;
        try {
            fetchResult = ManifestClient.fetchManifest(config);
        } catch (IOException e) {
            String errMsg = e.getMessage();
            System.out.println("[Lectern] Could not reach server: " + errMsg);
            System.out.println("[Lectern] Launching with current mods.");

            ui.setStatus("Could not reach server");
            ui.log("Error: " + errMsg);
            ui.showError("Could not reach the Lectern server.\nLaunching with your current mods.");
            ui.showLaunchButton();
            ui.waitForDismiss();
            System.exit(0);
            return;
        }

        ServerManifest manifest = fetchResult.getManifest();
        String sourceLabel = fetchResult.getSource() == ManifestClient.Source.RELAY ? "relay" : "direct";

        ui.log("Fetched manifest via " + sourceLabel);
        System.out.println("[Lectern] Fetched manifest via " + sourceLabel);

        if (fetchResult.getSource() == ManifestClient.Source.DIRECT) {
            // If we had to fall back to direct, log a note
            ui.log("Note: relay was unreachable, used direct connection");
            System.out.println("[Lectern] Note: relay was unreachable, used direct connection");
        }

        ui.setStatus("Checking mods...");
        ui.setDetail("Manifest version " + manifest.getManifestVersion() + " \u2014 " + manifest.getMods().size() + " mod(s)");
        ui.log("Manifest version: " + manifest.getManifestVersion());
        ui.log("Server has " + manifest.getMods().size() + " mod(s)");

        System.out.println("[Lectern] Manifest version: " + manifest.getManifestVersion());
        System.out.println("[Lectern] Server mods: " + manifest.getMods().size());

        // Sync mods
        File modsDir = new File(instanceDir, "mods");
        if (!modsDir.exists()) {
            modsDir.mkdirs();
        }

        try {
            ModSyncer syncer = new ModSyncer(modsDir, instanceDir, config);
            ModSyncer.SyncResult result = syncer.sync(manifest, ui);

            if (result.getDownloaded() == 0 && result.getRemoved() == 0) {
                System.out.println("[Lectern] Mods are up to date.");
                ui.setStatus("Mods are up to date");
                ui.log("All mods are up to date.");
            } else {
                if (result.getDownloaded() > 0) {
                    System.out.println("[Lectern] Downloaded " + result.getDownloaded() + " mod(s).");
                }
                if (result.getRemoved() > 0) {
                    System.out.println("[Lectern] Removed " + result.getRemoved() + " old mod(s).");
                }
                ui.setStatus("Sync complete");
                ui.setDetail("Downloaded " + result.getDownloaded() + ", removed " + result.getRemoved() + " mod(s)");
            }

            if (result.getFailed() > 0) {
                System.out.println("[Lectern] Warning: " + result.getFailed() + " mod(s) failed to download.");
                ui.showError(result.getFailed() + " mod(s) failed to download.\nThe game will launch with your current mods.");
            }

            System.out.println("[Lectern] Sync complete. Launching Minecraft...");
            ui.log("Sync complete. Launching Minecraft...");

            // Update servers.dat with the server address from the manifest
            String serverAddress = manifest.getServerAddress();
            String serverName = manifest.getServerName() != null ? manifest.getServerName() : config.getServerName();
            if (serverAddress != null && !serverAddress.isEmpty()) {
                try {
                    ServersDatWriter.updateServer(instanceDir, serverName, serverAddress);
                    ui.log("Server list updated: " + serverName + " \u2192 " + serverAddress);
                    System.out.println("[Lectern] Updated servers.dat: " + serverName + " \u2192 " + serverAddress);
                } catch (IOException e) {
                    System.err.println("[Lectern] Failed to update servers.dat: " + e.getMessage());
                    ui.log("Warning: Could not update server list: " + e.getMessage());
                }
            }

            if (ui.hasErrors()) {
                // Errors: show the launch button and wait
                ui.showLaunchButton();
                ui.waitForDismiss();
            } else {
                // Clean sync: auto-close after a brief moment so the player sees it
                ui.autoCloseAfter(1500);
                // Wait for the dialog to close before exiting
                try { Thread.sleep(1600); } catch (InterruptedException ignored) {}
            }
        } catch (IOException e) {
            System.err.println("[Lectern] Sync error: " + e.getMessage());
            System.out.println("[Lectern] Launching with current mods.");

            ui.setStatus("Sync failed");
            ui.log("Error: " + e.getMessage());
            ui.showError("Sync failed: " + e.getMessage() + "\nLaunching with your current mods.");
            ui.showLaunchButton();
            ui.waitForDismiss();
        }

        System.exit(0);
    }

    /**
     * Find the instance directory by looking for lectern.json in the current
     * directory or parent directory.
     */
    private static File findInstanceDir() {
        // Check current directory first
        File current = new File(System.getProperty("user.dir"));
        if (new File(current, CONFIG_FILE).exists()) {
            return current;
        }

        // Some launchers run from .minecraft or a subdirectory
        File parent = current.getParentFile();
        if (parent != null && new File(parent, CONFIG_FILE).exists()) {
            return parent;
        }

        return null;
    }
}
