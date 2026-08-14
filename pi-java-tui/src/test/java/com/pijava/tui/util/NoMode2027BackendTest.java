package com.pijava.tui.util;

import java.lang.reflect.Field;

import dev.tamboui.backend.panama.PanamaBackend;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the DECRPM leak: raw mode must not emit the
 * {@code CSI ? 2027 $ p} query whose late/split ConPTY response was inserted
 * into the editor as {@code 2027;3$y}.
 */
class NoMode2027BackendTest {

    @Test
    void rawModeDoesNotEmitMode2027Query() throws Exception {
        NoMode2027Backend backend;
        try {
            backend = new NoMode2027Backend();
        } catch (Exception e) {
            // CI/headless runs have no console handle; the handshake-free
            // contract only matters on a real terminal.
            Assumptions.assumeTrue(false, "real terminal required: " + e.getMessage());
            return;
        }

        try {
            backend.enableRawMode();

            // Stock PanamaBackend.enableRawMode() would have buffered the
            // DECRQM query bytes here; the safe backend must leave the
            // output buffer untouched.
            Field bufferField = PanamaBackend.class.getDeclaredField("outputBuffer");
            bufferField.setAccessible(true);
            Object buffer = bufferField.get(backend);
            int buffered = (int) buffer.getClass().getMethod("length").invoke(buffer);
            assertThat(buffered).isZero();
        } finally {
            backend.disableRawMode();
            backend.close();
        }
    }
}
