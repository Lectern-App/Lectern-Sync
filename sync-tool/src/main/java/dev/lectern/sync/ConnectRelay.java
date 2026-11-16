package dev.lectern.sync;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

final class ConnectRelay {
    private ConnectRelay() {}

    static String address(String descriptor, String deviceKey, Path device) throws Exception {
        String origin = JsonHelper.getString(descriptor, "relay");
        String id = JsonHelper.getString(descriptor, "server");
        String setup = JsonHelper.getString(descriptor, "setup");
        if (origin == null || id == null || !id.matches("[0-9a-f-]{36}")
                || deviceKey == null || !deviceKey.matches("nodekey:[0-9a-f]{64}")) {
            throw new IOException("This Prism instance is missing its Connect setup.");
        }
        URI uri = new URI(origin);
        boolean loopback = "http".equals(uri.getScheme())
                && ("127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost()));
        if ((!loopback && !"https".equals(uri.getScheme())) || uri.getHost() == null
                || !uri.getPath().isEmpty() || uri.getUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IOException("This instance has an invalid Lectern Relay address.");
        }

        String binding = hex(MessageDigest.getInstance("SHA-256")
                .digest((origin + "/" + id + "/" + deviceKey).getBytes(StandardCharsets.UTF_8)));
        Path grant = device.resolveSibling(device.getFileName() + "." + binding + ".grant");
        Path enrolled = grant.resolveSibling(grant.getFileName() + ".enrolled");
        if (!Files.exists(grant)) {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            try {
                Files.write(grant, hex(random).getBytes(StandardCharsets.US_ASCII), StandardOpenOption.CREATE_NEW);
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
                // A second launch already created the same local credential.
            }
        }
        if (Files.size(grant) != 64) throw new IOException("The saved Connect credential is invalid.");
        String secret = new String(Files.readAllBytes(grant), StandardCharsets.US_ASCII);
        if (!secret.matches("[0-9a-f]{64}")) throw new IOException("The saved Connect credential is invalid.");
        String claim = !Files.exists(enrolled) && setup != null && setup.matches("[0-9a-f]{64}")
                ? ",\"setup\":\"" + setup + "\"" : "";
        byte[] request = ("{\"device\":\"" + deviceKey + "\"" + claim + "}").getBytes(StandardCharsets.UTF_8);

        HttpURLConnection connection = (HttpURLConnection) new URI(origin + "/v2/connect/play/" + id).toURL().openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(10000);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + secret);
            connection.setFixedLengthStreamingMode(request.length);
            connection.setDoOutput(true);
            try (OutputStream out = connection.getOutputStream()) { out.write(request); }
            switch (connection.getResponseCode()) {
                case 200:
                    String address = JsonHelper.getString(readResponse(connection.getInputStream()), "address");
                    if (address == null) throw new IOException("The host did not return a Connect address.");
                    try {
                        Files.createFile(enrolled);
                    } catch (java.nio.file.FileAlreadyExistsException ignored) {
                        // The existing credential already completed setup.
                    }
                    return address;
                case 403:
                    throw new IOException("Access was removed or setup expired. Open the group link and download again.");
                case 404:
                    throw new IOException("This server doesn’t exist or may be inactive.");
                case 503:
                    throw new IOException("The host doesn’t seem to be running. Try again later.");
                default:
                    throw new IOException("Connect couldn’t reach the server through Lectern Relay.");
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String readResponse(InputStream input) throws IOException {
        try (InputStream in = input; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (bytes.size() + count > 16384) throw new IOException("Invalid Connect response.");
                bytes.write(buffer, 0, count);
            }
            return bytes.toString("UTF-8");
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }
}
