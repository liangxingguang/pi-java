package com.pijava.tui.screen;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.AgentSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

    /** 隔离 home：真 settings 的 defaultModel 会决定模型与可用思考档集合。 */
    @TempDir
    Path home;

    private String savedHome;

    @BeforeEach
    void isolateHome() {
        // settings-json-test-inheritance（memory）：不隔离 home 会读真
        // ~/.pi-java/agent/settings.json。L5 W6（docs/25 §7）后其中未编目的自定义
        // id 会回落到 provider 的目录首条，思考档集合随之改变。
        // ⚠️ user.home 保持到 tearDown（settings 懒解析＋close flush——create 后
        // 立即恢复会让首次访问与 flush 落进真实 home）。
        savedHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
    }

    @AfterEach
    void restoreHome() {
        System.setProperty("user.home", savedHome);
    }

    private AgentSession session() {
        // 显式钉一个未编目的自定义 id：可用档列表落「能力未知 ⇒ 七档全集」
        // （{@code ThinkingCommands.availableLevels}），这才是本测试要验的预选/
        // 移动语义。L5 W6（docs/25 §7）之前它由真 home settings 的自定义
        // defaultModel 偶然提供；W6 后裸未知 id 报错、settings 的自定义 id 会
        // 回落到目录模型，可用档集合随之收窄 ⇒ 夹具改为显式给出。
        return AgentSession.create(ArgsParser.parse(new String[] {
            "--provider", "anthropic", "--model", "claude-sonnet-4-5-custom",
            "--session-dir", sessionDir.toString()}));
    }

    @Test
    void enterOnPreselectedCurrentLevelKeepsItAndReports() throws Exception {
        try (var session = session()) {
            var current = session.harness().getThinkingLevel();
            var statuses = new ArrayList<String>();
            var screen = new ThinkingLevelScreen(session,
                new com.pijava.coding.agent.core.KeybindingsManager(), () -> 80, statuses::add);

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
            var screen = new ThinkingLevelScreen(session,
                new com.pijava.coding.agent.core.KeybindingsManager(), () -> 80, statuses::add);

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
            var screen = new ThinkingLevelScreen(session,
                new com.pijava.coding.agent.core.KeybindingsManager(), () -> 80, statuses::add);

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
            var screen = new ThinkingLevelScreen(session,
                new com.pijava.coding.agent.core.KeybindingsManager(), () -> 80, s -> { });
            assertThat(screen.render()).isNotNull();
        }
    }
}
