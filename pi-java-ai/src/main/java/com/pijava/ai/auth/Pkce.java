package com.pijava.ai.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * PKCE 工具（pi {@code auth/oauth/pkce.ts}）：32 字节随机 verifier、
 * SHA-256 challenge，base64url **无填充**。三个浏览器登录流共用。
 */
final class Pkce {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Pkce() {}

    /** 生成一对 PKCE verifier/challenge（{@code PkcePair(verifier, challenge)}）。 */
    static PkcePair generate() {
        var verifier = newVerifier();
        return new PkcePair(verifier, challenge(verifier));
    }

    /** 32 随机字节的 base64url（无填充）。 */
    static String newVerifier() {
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return base64Url(bytes);
    }

    /** 任意随机值（ChatGPT 的 state/nonce）：与 verifier 同形（pi {@code randomBytes(32).toString("base64url")}）。 */
    static String randomValue() {
        return newVerifier();
    }

    /** S256 challenge：SHA-256(verifier) 的 base64url（无填充）。 */
    static String challenge(String verifier) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return base64Url(digest.digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 一对 PKCE 值。 */
    record PkcePair(String verifier, String challenge) {}
}
