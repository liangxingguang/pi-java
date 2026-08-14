package com.pijava.tui.util;

import dev.tamboui.backend.jline3.JLineBackend;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * JLine backend variant must also skip the Mode 2027 handshake so the
 * Windows console keeps working without leaking DECRPM responses.
 */
class NoMode2027JLineBackendTest {

    @Test
    void rawModeSkipsMode2027Handshake() throws Exception {
        NoMode2027JLineBackend backend;
        try {
            backend = new NoMode2027JLineBackend();
        } catch (Exception e) {
            // No real terminal in CI/headless runs.
            Assumptions.assumeTrue(false, "real terminal required: " + e.getMessage());
            return;
        }

        try {
            backend.enableRawMode();

            // Stock JLineBackend.enableRawMode() sets mode2027Enabled when the
            // handshake succeeds; the safe variant must never touch it.
            var modeField = JLineBackend.class.getDeclaredField("mode2027Enabled");
            modeField.setAccessible(true);
            assertThat(modeField.getBoolean(backend)).isFalse();
        } finally {
            backend.disableRawMode();
            backend.close();
        }
    }
}
