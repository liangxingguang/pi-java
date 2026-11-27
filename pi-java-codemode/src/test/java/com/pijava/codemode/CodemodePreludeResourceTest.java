package com.pijava.codemode;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.junit.jupiter.api.Test;

/** Tests for the bundled JavaScript prelude resource. */
class CodemodePreludeResourceTest {
    private static final String RESOURCE = "codemode-prelude.js";
    private static final int EXPECTED_BYTES = 17_349;
    private static final String EXPECTED_SHA256 = "5ce4f673bcd93c5e6148743452b250fd45b7e3441a4ffd78029af0ac5528a6bd";

    @Test
    void resourceExistsAndContainsRequiredMarkers() throws IOException {
        String text = readResource();
        assertThat(text).isNotBlank();
        assertThat(text).startsWith("(function (bridge, toolsJson, globalsJson, storeJson) {");
        assertThat(text).contains("function lockdown()");
        assertThat(text).contains("function caller(kind, name, spread)");
        assertThat(text).contains("ALL_TOOLS");
        assertThat(text).contains("function store(key, value)");
        assertThat(text).contains("function load(key)");
        assertThat(text).contains("function text(value)");
        assertThat(text).contains("function image(value)");
        assertThat(text).contains("function exit()");
        assertThat(text).contains("stalled()");
    }

    @Test
    void resourceBytesAndHashAreLocked() throws IOException, NoSuchAlgorithmException {
        byte[] bytes = readBytes();
        assertThat(bytes).hasSize(EXPECTED_BYTES);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder hash = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            hash.append(String.format("%02x", value));
        }
        assertThat(hash.toString()).isEqualTo(EXPECTED_SHA256);
    }

    private static String readResource() throws IOException {
        return new String(readBytes(), StandardCharsets.UTF_8);
    }

    private static byte[] readBytes() throws IOException {
        try (InputStream stream = CodemodePreludeResourceTest.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            assertThat(stream).isNotNull();
            return stream.readAllBytes();
        }
    }
}
