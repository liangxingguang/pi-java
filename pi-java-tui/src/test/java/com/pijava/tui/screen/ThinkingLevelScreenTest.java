package com.pijava.tui.screen;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.AgentSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 批 15（T8/裁决⑥）：ThinkingLevelScreen 预选／Enter 设级／Esc 取消。 */
class ThinkingLevelScreenTest {

    private static final dev.tamboui.tui.event.KeyEvent ENTER =
        dev.tamboui.tui.event.KeyEvent.ofKey(dev.tamboui.tui.event.KeyCode.ENTER);
    private static final dev.tamboui.tui.event.KeyEvent DOWN =
        dev.tamboui.tui.event.KeyEvent.ofKey(dev.tamboui.tui.event.KeyCode.DOWN);
    private static final dev.tamboui.tui.event.KeyEvent ESC =
        dev.tamboui.tui.event.KeyEvent.ofKey(dev.tamboui.tui.event.KeyCode.ESCAPE);

    @TempDir
    Path sessionDir;

    private AgentSession session() {
        return AgentSession.create(
            ArgsParser.parse(new String[] {"--session-dir", sessionDir.toString()}));
    }

    @Test
    void enterOnPreselectedCurrentLevelKeepsItAndReports() throws Exception {
        try (var session = session()) {
            var current = session.harness().getThinkingLevel();
            var statuses = new ArrayList<String>();
            var screen = new ThinkingLevelScreen(session, statuses::add);

            screen.onKeyEvent(ENTER); // 当前级预选 ⇒ 直接 Enter
            assertThat(screen.isDone()).isTrue();
            screen.apply(session, s -> { });

            assertThat(session.harness().getThinkingLevel()).isEqualTo(current);
            assertThat(statuses).containsExactly("Thinking level: " + current.label());
        }
    }

    @Test
    void enterOnMovedSelectionSetsLevel() throws Exception {
        try (var session = session()) {
            session.setThinkingLevel(ModelThinkingLevel.off());
            var statuses = new ArrayList<String>();
            var screen = new ThinkingLevelScreen(session, statuses::add);

            screen.onKeyEvent(DOWN);  // off ⇒ minimal
            screen.onKeyEvent(ENTER);
            screen.apply(session, s -> { });

            assertThat(session.harness().getThinkingLevel())
                .isEqualTo(ModelThinkingLevel.of(
                    new com.pijava.ai.thinking.ThinkingLevel.Minimal()));
            assertThat(statuses).containsExactly("Thinking level: minimal");
        }
    }

    @Test
    void escapeCancelsWithoutSetting() throws Exception {
        try (var session = session()) {
            var before = session.harness().getThinkingLevel();
            var statuses = new ArrayList<String>();
            var screen = new ThinkingLevelScreen(session, statuses::add);

            screen.onKeyEvent(DOWN);
            screen.onKeyEvent(ESC);
            assertThat(screen.isDone()).isTrue();
            screen.apply(session, s -> { });

            assertThat(session.harness().getThinkingLevel()).isEqualTo(before);
            assertThat(statuses).isEmpty();
        }
    }

    @Test
    void renderShowsTitle() throws Exception {
        try (var session = session()) {
            var screen = new ThinkingLevelScreen(session, s -> { });
            assertThat(screen.render()).isNotNull();
        }
    }
}
