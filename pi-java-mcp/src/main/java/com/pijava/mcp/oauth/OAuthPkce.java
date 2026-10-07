package com.pijava.mcp.oauth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * PKCE S256 verifier and challenge (pi flow.ts:147-152).
 */
final class OAuthPkce {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private OAuthPkce() {
    }

    /**
     * A fresh verifier and its challenge.
     *
     * @param verifier the code verifier sent to the token endpoint
     * @param challenge the S256 challenge sent to the authorization endpoint
     */
    record Pair(String verifier, String challenge) {
    }

    static Pair generate() {
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        var verifier = ENCODER.encodeToString(bytes);
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
        return new Pair(verifier, ENCODER.encodeToString(digest));
    }
}
