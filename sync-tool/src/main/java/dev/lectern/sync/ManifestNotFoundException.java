package dev.lectern.sync;

import java.io.IOException;

/**
 * Thrown when every configured source (relay + direct) responded successfully
 * but no source knows about this server_id (all returned HTTP 404).
 *
 * This is distinct from a connection failure: the relay answered, it just has
 * no record of this server. Most likely the server was removed or its TTL
 * lapsed (30 days after last update). The UI should treat this as a terminal
 * "server unavailable" state rather than a transient network error.
 */
public class ManifestNotFoundException extends IOException {
    public ManifestNotFoundException(String message) {
        super(message);
    }
}
