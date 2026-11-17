package dev.lectern.sync;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Starts the gameplay tunnel and keeps it alive for the game process. */
public final class ConnectLaunch {
    private ConnectLaunch() {}

    public static void premain(String options) {
        // Prism starts Minecraft in the instance's game directory.
        Helper helper = new Helper();
        try {
            Path minecraft = Paths.get("").toRealPath();
            connect(helper, minecraft, minecraft.resolve(".lectern/connect.json"), deviceFile(minecraft));
            Runtime.getRuntime().addShutdownHook(new Thread(helper::close, "lectern-connect-cleanup"));
        } catch (Exception e) {
            helper.close();
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            System.err.println("[Lectern Connect] " + e.getMessage());
            System.exit(1);
        }
    }

    static int run(String[] args) {
        try {
            if (args.length < 2) throw new IOException("The Minecraft instance folder is required.");
            Path minecraft = Paths.get(args[1]).toRealPath();
            if (!Files.isDirectory(minecraft)) throw new IOException("The Minecraft instance folder does not exist.");
            Path state = minecraft.resolve(".lectern");
            Files.createDirectories(state);
            Path device = deviceFile(minecraft);
            try (Helper helper = new Helper()) {
                if ("connect-keygen".equals(args[0])) {
                    if (args.length != 2) throw new IOException("Usage: connect-keygen <minecraft folder>");
                    printPublicKey(helper, device);
                    return 0;
                }
                if (args.length < 3) throw new IOException("Prism must supply the Minecraft command to the wrapper.");
                return play(helper, minecraft, state.resolve("connect.json"), device,
                    Arrays.copyOfRange(args, 2, args.length));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 1;
        } catch (Exception e) {
            System.err.println("[Lectern Connect] " + e.getMessage());
            return 1;
        }
    }

    private static Path deviceFile(Path minecraft) throws Exception {
        Path directory = Paths.get(System.getProperty("user.home"), ".lectern", "connect", "devices");
        if (directory.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rwx------")));
        } else {
            Files.createDirectories(directory);
        }
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(minecraft.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder name = new StringBuilder();
        for (byte value : hash) name.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return directory.resolve(name + ".json");
    }

    private static void printPublicKey(Helper helper, Path device) throws Exception {
        System.out.println(deviceKey(helper, device));
    }

    private static String deviceKey(Helper helper, Path device) throws Exception {
        if (!Files.exists(device)) {
            helper.start("keygen");
            helper.process.getOutputStream().close();
            String key = response(helper.process, 5);
            if (!helper.process.waitFor(5, TimeUnit.SECONDS) || helper.process.exitValue() != 0
                    || key == null || JsonHelper.getString(key, "public_key") == null
                    || JsonHelper.getString(key, "private_key") == null) {
                throw new IOException("The helper could not generate a device key.");
            }
            Files.write(device, key.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
        }
        String publicKey = JsonHelper.getString(read(device), "public_key");
        if (publicKey == null || !publicKey.matches("nodekey:[0-9a-fA-F]{64}")) {
            throw new IOException("The device key file is invalid.");
        }
        return publicKey;
    }

    private static void connect(Helper helper, Path minecraft, Path setup, Path device) throws Exception {
        if (!Files.isRegularFile(setup)) {
            throw new IOException("Download the Prism instance from your group share link.");
        }
        String descriptor = read(setup);
        String address = JsonHelper.getString(descriptor, "address");
        if (JsonHelper.getString(descriptor, "relay") != null) {
            try (Helper keyGenerator = new Helper()) {
                address = ConnectRelay.address(descriptor, deviceKey(keyGenerator, device), device);
            }
        } else if (!Files.isRegularFile(device)) {
            throw new IOException("Pair this Prism instance with Lectern Connect before playing.");
        }
        String privateKey = JsonHelper.getString(read(device), "private_key");
        long port = JsonHelper.getLong(descriptor, "local_port", 0);
        if (address == null || !address.matches("tc[A-Za-z0-9_+/=-]+") || address.length() > 8192
                || privateKey == null || !privateKey.matches("privkey:[0-9a-fA-F]{64}")
                || port < 0 || port > 65535) {
            throw new IOException("The Lectern Connect setup is invalid.");
        }

        helper.start("join");
        OutputStream input = helper.process.getOutputStream();
        String request = "{\"address\":\"" + address + "\",\"private_key\":\"" + privateKey
            + "\",\"local_port\":" + port + "}\n";
        input.write(request.getBytes(StandardCharsets.UTF_8));
        input.flush();
        String reply = response(helper.process, 35);
        String localAddress = reply == null ? null : JsonHelper.getString(reply, "local_address");
        if (localAddress == null || !localAddress.matches("127\\.0\\.0\\.1:[0-9]{1,5}")) {
            throw new IOException("The tunnel did not become ready. Check the host and its device access list.");
        }
        ServersDatWriter.updateServer(minecraft.toFile(), "Lectern Connect", localAddress);
        System.out.println("[Lectern Connect] Gameplay tunnel ready at " + localAddress);
    }

    private static int play(Helper helper, Path minecraft, Path setup, Path device, String[] command) throws Exception {
        connect(helper, minecraft, setup, device);
        Process game = new ProcessBuilder(command).directory(minecraft.toFile()).inheritIO().start();
        Thread shutdown = new Thread(() -> {
            game.destroy();
            helper.close();
        }, "lectern-connect-cleanup");
        Runtime.getRuntime().addShutdownHook(shutdown);
        try {
            return game.waitFor();
        } finally {
            if (game.isAlive()) game.destroy();
            try {
                Runtime.getRuntime().removeShutdownHook(shutdown);
            } catch (IllegalStateException ignored) {
                // The JVM is shutting down and the hook is already running.
            }
        }
    }

    private static String read(Path file) throws IOException {
        if (Files.size(file) > 16384) throw new IOException("The Lectern Connect configuration is too large.");
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static String response(Process process, int seconds) throws Exception {
        FutureTask<String> result = new FutureTask<>(() ->
            new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)).readLine());
        Thread reader = new Thread(result, "lectern-connect-ready");
        reader.setDaemon(true);
        reader.start();
        return result.get(seconds, TimeUnit.SECONDS);
    }

    private static final class Helper implements AutoCloseable {
        private Path directory;
        private Path binary;
        private Process process;

        void start(String mode) throws IOException {
            String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
            String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
            String platform = os.contains("win") ? "windows" : os.contains("mac") ? "darwin" : os.contains("linux") ? "linux" : "";
            String machine = arch.equals("amd64") || arch.equals("x86_64") ? "amd64" :
                arch.equals("aarch64") || arch.equals("arm64") ? "arm64" : "";
            String filename = platform.equals("windows") ? "lectern-connect.exe" : "lectern-connect";
            String resource = "/connect/" + platform + "-" + machine + "/" + filename;
            try (InputStream source = ConnectLaunch.class.getResourceAsStream(resource)) {
                if (source == null) throw new IOException("This Lectern Sync JAR has no Connect helper for " + os + " " + arch + ".");
                directory = Files.createTempDirectory("lectern-connect-");
                binary = directory.resolve(filename);
                Files.copy(source, binary);
                if (!platform.equals("windows") && !binary.toFile().setExecutable(true, true)) {
                    throw new IOException("Could not make the bundled Connect helper executable.");
                }
            }
            process = new ProcessBuilder(binary.toString(), mode).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        }

        @Override
        public synchronized void close() {
            if (process != null) {
                try {
                    process.getOutputStream().close();
                    if (!process.waitFor(3, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                        process.waitFor(3, TimeUnit.SECONDS);
                    }
                } catch (IOException e) {
                    process.destroyForcibly();
                } catch (InterruptedException e) {
                    process.destroyForcibly();
                    Thread.currentThread().interrupt();
                }
            }
            try {
                if (binary != null) Files.deleteIfExists(binary);
                if (directory != null) Files.deleteIfExists(directory);
            } catch (IOException ignored) {
                if (directory != null) directory.toFile().deleteOnExit();
                if (binary != null) binary.toFile().deleteOnExit();
            }
        }
    }
}
