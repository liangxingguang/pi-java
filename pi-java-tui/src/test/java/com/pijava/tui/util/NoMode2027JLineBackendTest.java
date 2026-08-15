package com.pijava.tui.util;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;

import dev.tamboui.backend.jline3.JLineBackend;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

import static com.pijava.tui.util.NoMode2027JLineBackend.translate;

/**
 * JLine backend variant must also skip the Mode 2027 handshake so the
 * Windows console keeps working without leaking DECRPM responses.
 */
class NoMode2027JLineBackendTest {

    /** Scripted byte source for the X10→SGR translator. */
    private static final class Script {
        private final List<Integer> bytes;
        private int pos;

        Script(int... bytes) {
            this.bytes = Arrays.stream(bytes).boxed().toList();
        }

        int peek() {
            return pos < bytes.size() ? bytes.get(pos) : -2;
        }

        int read() {
            return pos < bytes.size() ? bytes.get(pos++) : -2;
        }
    }

    @Test
    void translateConvertsX10MouseToSgr() throws Exception {
        // The ESC byte is already consumed by the backend before translate().
        var script = new Script('[', 'M', 96, 42, 52);
        var out = new ArrayDeque<Integer>();

        int emitted = translate(27, script::read, script::peek, out);

        assertThat(emitted).isEqualTo(27);
        assertThat(out).containsExactly(
            (int) '<', (int) '6', (int) '4', (int) ';',
            (int) '1', (int) '0', (int) ';', (int) '2', (int) '0', (int) 'M');
    }

    @Test
    void translateLeavesArrowKeysIntact() throws Exception {
        var script = new Script('[', 'A');
        var out = new ArrayDeque<Integer>();

        int emitted = translate(27, script::read, script::peek, out);

        assertThat(emitted).isEqualTo(27);
        assertThat(out).containsExactly((int) '[');
        assertThat(script.read()).isEqualTo('A');
    }

    @Test
    void translateLeavesStandaloneEscAlone() throws Exception {
        var script = new Script();
        var out = new ArrayDeque<Integer>();

        assertThat(translate(27, script::read, script::peek, out)).isEqualTo(27);
        assertThat(out).isEmpty();
    }

    @Test
    void translateQueuesIncompleteSequenceForTheParser() throws Exception {
        var script = new Script('[', 'M', 96);
        var out = new ArrayDeque<Integer>();

        assertThat(translate(27, script::read, script::peek, out)).isEqualTo(27);
        assertThat(out).containsExactly((int) '[', (int) 'M', 96);
    }

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
