package com.pijava.tui.screen;

import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.ai.thinking.ModelThinkingLevel;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 34（S8）：thinking 选择器的完整键面与渲染行形——pi
 * {@code components/thinking-selector.ts} 对位（DEFAULT 标记、ctrl+s 存默认、
 * 描述列、搜索行）。
 */
class ThinkingLevelScreenS8Test {

    private final KeybindingsManager keys = new KeybindingsManager();

    private static KeyEvent ctrl(char key) {
        return KeyEvent.ofChar(key, KeyModifiers.CTRL);
    }

    private ThinkingLevelScreen screen(AgentSession session,
                                       java.util.List<String> statuses) {
        return new ThinkingLevelScreen(session, keys, () -> 60, statuses::add);
    }

    @Test
    void layoutShowsTitleCycleHintDescriptionAndDefaultTag() throws Exception {
        try (var session = AgentSession.create(
                ArgsParser.parse(new String[] {"--models", "gemini-2.5*"}))) {
            session.services().settings().accessors().setDefaultThinkingLevel("medium");
            var screen = screen(session, new java.util.ArrayList<>());

            var text = visibleText(screen.layout());
            assertThat(text).contains(" Thinking Level")
                .contains("cycles thinking levels in-session")
                .contains("Moderate reasoning (~8k tokens) · default")
                .contains("Very brief reasoning (~1k tokens)");
        }
    }

    @Test
    void defaultTagFallsBackToMediumWhenSettingMissing() throws Exception {
        try (var session = AgentSession.create(
                ArgsParser.parse(new String[] {"--models", "gemini-2.5*"}))) {
            session.services().settings().accessors().setDefaultThinkingLevel(null);
            var screen = screen(session, new java.util.ArrayList<>());

            assertThat(visibleText(screen.layout()))
                .contains("Moderate reasoning (~8k tokens) · default");
        }
    }

    @Test
    void selectionMarksCurrentLevelWithCheck() throws Exception {
        try (var session = AgentSession.create(
                ArgsParser.parse(new String[] {"--models", "gemini-2.5*"}))) {
            session.setThinkingLevel(ModelThinkingLevel.off());
            var screen = screen(session, new java.util.ArrayList<>());

            assertThat(visibleText(screen.layout())).contains(" ✓ off");
        }
    }

    @Test
    void ctrlSSavesAsDefaultAndSetsSessionLevel() throws Exception {
        try (var session = AgentSession.create(
                ArgsParser.parse(new String[] {"--models", "gemini-2.5*"}))) {
            session.setThinkingLevel(ModelThinkingLevel.off());
            var statuses = new java.util.ArrayList<String>();
            var screen = screen(session, statuses);
            assertThat(visibleText(screen.layout())).contains(" ✓ off");

            screen.onKeyEvent(ctrl('s'));   // 选中项 off ⇒ persist
            assertThat(screen.isDone()).isTrue();
            screen.apply(session, s -> { });

            assertThat(session.harness().getThinkingLevel())
                .isEqualTo(ModelThinkingLevel.off());
            assertThat(session.services().settings().accessors().getDefaultThinkingLevel())
                .isEqualTo("off");
            assertThat(statuses).containsExactly("Default thinking level: off");
        }
    }

    @Test
    void enterSetsSessionLevelOnly() throws Exception {
        try (var session = AgentSession.create(
                ArgsParser.parse(new String[] {"--models", "gemini-2.5*"}))) {
            session.setThinkingLevel(ModelThinkingLevel.off());
            session.services().settings().accessors().setDefaultThinkingLevel("medium");
            var statuses = new java.util.ArrayList<String>();
            var screen = screen(session, statuses);

            screen.onKeyEvent(KeyEvent.ofKey(KeyCode.DOWN));   // off ⇒ 下一级
            screen.onKeyEvent(KeyEvent.ofKey(KeyCode.ENTER));
            screen.apply(session, s -> { });

            assertThat(session.services().settings().accessors().getDefaultThinkingLevel())
                .isEqualTo("medium");   // 未被改写
            assertThat(statuses).containsExactly(
                "Thinking level: " + session.harness().getThinkingLevel().label());
        }
    }

    @Test
    void filterMatchesDescriptionsAndRendersQueryRow() throws Exception {
        try (var session = AgentSession.create(
                ArgsParser.parse(new String[] {"--models", "gemini-2.5*"}))) {
            var screen = screen(session, new java.util.ArrayList<>());
            for (char c : "8k".toCharArray()) {
                screen.onKeyEvent(KeyEvent.ofChar(c));
            }

            var text = visibleText(screen.layout());
            assertThat(text).contains(" > 8k")
                .contains("Moderate reasoning (~8k tokens)")
                .doesNotContain("Very brief reasoning");
        }
    }

    @Test
    void escapeClearsFilterThenCancelsWithoutSetting() throws Exception {
        try (var session = AgentSession.create(
                ArgsParser.parse(new String[] {"--models", "gemini-2.5*"}))) {
            var before = session.harness().getThinkingLevel();
            var statuses = new java.util.ArrayList<String>();
            var screen = screen(session, statuses);

            screen.onKeyEvent(KeyEvent.ofChar('x'));
            screen.onKeyEvent(KeyEvent.ofKey(KeyCode.ESCAPE));   // 先清 filter
            screen.onKeyEvent(KeyEvent.ofKey(KeyCode.ESCAPE));   // 再取消
            assertThat(screen.isDone()).isTrue();
            screen.apply(session, s -> { });

            assertThat(session.harness().getThinkingLevel()).isEqualTo(before);
            assertThat(statuses).isEmpty();
        }
    }

    private static String visibleText(java.util.List<java.util.List<
            dev.tamboui.text.Span>> layout) {
        var sb = new StringBuilder();
        for (var row : layout) {
            for (var span : row) {
                sb.append(span.content());
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
