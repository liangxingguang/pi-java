package com.pijava.ai.auth;

import java.util.Optional;

/**
 * Abstraction for resolving API credentials.
 *
 * <p>Implementations may read from environment variables, system
 * keychains, OAuth flows, or encrypted config files.</p>
 */
public interface CredentialStore {

    /** Resolve an API key for the given provider. */
    Optional<String> resolveApiKey(String provider);

    /** Store an API key for the given provider. */
    void storeApiKey(String provider, String apiKey);

    /** Remove a stored API key. */
    void deleteApiKey(String provider);
}
