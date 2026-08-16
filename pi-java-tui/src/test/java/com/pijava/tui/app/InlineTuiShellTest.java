package com.pijava.tui.app;

import java.util.List;

import com.pijava.tui.util.InlineTuiShell;

import dev.tamboui.text.Line;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Raw-scrollback shell behavior: transcript lines append to the main buffer
 * through the backend (no alternate screen), the streaming block rewrites in
 * place, and modal overlays switch to the alternate screen and back.
 */
class InlineTuiShellTest {

    @Test
    void printlnAppendsToScrollback() throws Exception {
        var backend = new FakeBackend();
        try (var shell = InlineTuiShell.createForTest(backend)) {
            shell.println("hello world");
            shell.println(Line.from("styled line"));
            String joined = String.join("", backend.rawWrites());
            assertThat(joined).contains("hello world");
            assertThat(joined).contains("styled line");
            assertThat(shell.printedLines()).isEqualTo(2);
        }
    }

    @Test
    void replaceLastBlockRewritesInPlace() throws Exception {
        var backend = new FakeBackend();
        try (var shell = InlineTuiShell.createForTest(backend)) {
            shell.println("one");
            shell.println("two");
            boolean ok = shell.replaceLastBlock(2,
                List.of(Line.from("AAA"), Line.from("BBB")));
            assertThat(ok).isTrue();
            String joined = String.join("", backend.rawWrites());
            assertThat(joined).contains("\u001b[2A");
            assertThat(joined).contains("AAA");
            assertThat(joined).contains("BBB");
        }
    }

    @Test
    void replaceLastBlockRefusesWhenBlockTooTall() throws Exception {
        var backend = new FakeBackend();
        try (var shell = InlineTuiShell.createForTest(backend)) {
            for (int i = 0; i < 40; i++) {
                shell.println("line " + i);
            }
            // FakeBackend reports a 30-row screen; a 30-row block cannot be
            // rewritten safely above a 2-row bottom region.
            assertThat(shell.replaceLastBlock(30, List.of(Line.from("x")))).isFalse();
        }
    }

    @Test
    void runLoopRendersAndStopsOnQuit() throws Exception {
        var backend = new FakeBackend();
        var shell = InlineTuiShell.createForTest(backend);
        var thread = Thread.startVirtualThread(() -> {
            try {
                shell.run(ignored -> { }, frame -> { });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        Thread.sleep(200);
        assertThat(backend.rawWrites()).isNotEmpty();
        shell.quit();
        thread.join(3000);
        assertThat(thread.isAlive()).isFalse();
        shell.close();
    }

    @Test
    void idleLoopDoesNotWriteAfterInitialRender() throws Exception {
        var backend = new FakeBackend();
        var shell = InlineTuiShell.createForTest(backend);
        var thread = Thread.startVirtualThread(() -> {
            try {
                shell.run(ignored -> { }, frame -> { });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        Thread.sleep(200);
        int writesAfterStart = backend.rawWrites().size();
        assertThat(writesAfterStart).isGreaterThan(0);
        Thread.sleep(300);
        // Idle: no events, no dirty marks — the loop must not repaint, or the
        // cursor jump to the bottom would undo the user's scrollback scroll.
        assertThat(backend.rawWrites().size()).isEqualTo(writesAfterStart);
        shell.quit();
        thread.join(3000);
        assertThat(thread.isAlive()).isFalse();
        shell.close();
    }

    @Test
    void overlaySwitchesAndRestores() throws Exception {
        var backend = new FakeBackend();
        try (var shell = InlineTuiShell.createForTest(backend)) {
            assertThat(shell.overlayActive()).isFalse();
            shell.beginOverlay();
            assertThat(shell.overlayActive()).isTrue();
            shell.endOverlay();
            assertThat(shell.overlayActive()).isFalse();
        }
    }
}
