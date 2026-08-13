package com.pijava.tui.app;

import java.util.List;

import com.pijava.agent.harness.SessionSnapshot;
import com.pijava.tui.screen.ChatScreen;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 §16: the full chat widget tree assembles without a terminal
 * (real-terminal smoke stays on the §13 manual checklist).
 */
class TuiAssemblyTest {

    @Test
    void chatTreeAssemblesWithoutRunner() {
        var screen = new ChatScreen();
        screen.updateSnapshot(new SessionSnapshot(
            "demo", "google/gemini-2.5-flash", "idle",
            123L, 2, List.of("read", "write"), List.of()));

        assertThat(screen.render()).isNotNull();
        assertThat(screen.statusBar()).isNotNull();
    }
}
