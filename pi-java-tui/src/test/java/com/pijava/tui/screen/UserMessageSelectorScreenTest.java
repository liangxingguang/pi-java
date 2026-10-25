package com.pijava.tui.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.pijava.coding.agent.core.AgentSession;
import com.pijava.tui.theme.SemanticColors;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link UserMessageSelectorScreen} 规格（docs/21 §6 tui ①–⑥）：
 * pi UserMessageList（user-message-selector.ts:14-107）逐字——初始选中、环绕
 * 导航、可视窗公式、行形、Enter/Esc 结局、容器头尾。overlay 直驱（批 8/9 手法）。
 */
class UserMessageSelectorScreenTest {

    private static final KeyEvent UP = new KeyEvent(KeyCode.UP, KeyModifiers.NONE, 0);
    private static final KeyEvent DOWN = new KeyEvent(KeyCode.DOWN, KeyModifiers.NONE, 0);
    private static final KeyEvent ENTER = new KeyEvent(KeyCode.ENTER, KeyModifiers.NONE, 0);
    private static final KeyEvent ESC = new KeyEvent(KeyCode.ESCAPE, KeyModifiers.NONE, 0);

    private static List<AgentSession.ForkMessage> messages(int n) {
        var list = new ArrayList<AgentSession.ForkMessage>();
        for (int i = 1; i <= n; i++) {
            list.add(new AgentSession.ForkMessage("e" + i, "message " + i));
        }
        return list;
    }

    private record Rig(UserMessageSelectorScreen screen,
                       AtomicReference<String> selected, AtomicBoolean cancelled) {}

    private static Rig rig(List<AgentSession.ForkMessage> messages,
                           String initialSelectedId, int cols) {
        var selected = new AtomicReference<String>();
        var cancelled = new AtomicBoolean();
        var screen = new UserMessageSelectorScreen(messages, initialSelectedId,
            () -> cols, selected::set, () -> cancelled.set(true));
        return new Rig(screen, selected, cancelled);
    }

    /** 定案并 apply（PiTuiApp 循环对位：键 → isDone → apply）。 */
    private static void drive(Rig rig, KeyEvent... keys) {
        for (var key : keys) {
            rig.screen().onKeyEvent(key);
        }
        assertThat(rig.screen().isDone()).as("overlay 定案").isTrue();
        rig.screen().apply(null, s -> { });
    }

    /** 每行 span 文本拼接（样式不计）。 */
    private static List<String> lineTexts(UserMessageSelectorScreen screen) {
        return screen.layout().stream()
            .map(row -> row.stream().map(UserMessageSelectorScreen.Span::text)
                .reduce("", String::concat))
            .toList();
    }

    // ── ① 初始选中 ────────────────────────────────────────────

    @Test
    void initialSelectionIsTheLastMessage() {
        assertThat(rig(messages(3), null, 80).screen().selectedIndex()).isEqualTo(2);
        // 初值命中用初值（pi :26-28）。
        assertThat(rig(messages(3), "e1", 80).screen().selectedIndex()).isEqualTo(0);
        // 初值未命中回落最后一条。
        assertThat(rig(messages(3), "nope", 80).screen().selectedIndex()).isEqualTo(2);
    }

    // ── ② 环绕导航（pi :84-91 逐字）────────────────────────────

    @Test
    void upAtTopWrapsToBottomAndDownAtBottomWrapsToTop() {
        var rig = rig(messages(3), null, 80);
        var screen = rig.screen();
        screen.onKeyEvent(UP);                    // 底（e3）→ e2
        assertThat(screen.selectedIndex()).isEqualTo(1);
        screen.onKeyEvent(UP);
        screen.onKeyEvent(UP);                    // 到顶 e1 再上 ⇒ 环绕到底 e3
        assertThat(screen.selectedIndex()).isEqualTo(2);
        screen.onKeyEvent(DOWN);                  // 到底再下 ⇒ 环绕到顶 e1
        assertThat(screen.selectedIndex()).isEqualTo(0);
        screen.onKeyEvent(DOWN);
        assertThat(screen.selectedIndex()).isEqualTo(1);
    }

    // ── ③ 可视窗滑动公式（pi :44-48）───────────────────────────

    @Test
    void visibleWindowSlidesAroundTheSelection() {
        // n=15：sel=0 ⇒ [0,10)；sel=7 ⇒ [2,12)；sel=14 ⇒ [5,15)。
        var top = rig(messages(15), "e1", 80).screen();
        assertThat(lineTexts(top)).containsSubsequence("  Message 1 of 15", "  Message 10 of 15")
            .doesNotContain("  Message 11 of 15");

        var mid = rig(messages(15), "e8", 80).screen();
        assertThat(lineTexts(mid)).doesNotContain("  Message 2 of 15")
            .containsSubsequence("  Message 3 of 15", "  Message 12 of 15")
            .doesNotContain("  Message 13 of 15");

        var bottom = rig(messages(15), "e15", 80).screen();
        assertThat(lineTexts(bottom)).doesNotContain("  Message 5 of 15")
            .containsSubsequence("  Message 6 of 15", "  Message 15 of 15");
    }

    // ── ④ 行形逐字 ────────────────────────────────────────────

    @Test
    void rowShapesFollowThePiLayout() {
        var rig = rig(List.of(
            new AgentSession.ForkMessage("e1", "first\nsecond "),
            new AgentSession.ForkMessage("e2", "plain")), "e2", 80);
        var rows = rig.screen().layout();

        // 选中行：光标 "› "（ACCENT）＋文本加粗；单行化（\n→空格 trim）。
        var cursorRow = rows.stream()
            .filter(r -> r.stream().anyMatch(s -> s.text().startsWith("›")))
            .findFirst().orElseThrow();
        assertThat(cursorRow.get(0).text()).isEqualTo("› ");
        assertThat(cursorRow.get(0).style()).isEqualTo(SemanticColors.accent());
        assertThat(cursorRow.get(1).text()).isEqualTo("plain");
        // 未选中行：两空格前缀、无加粗（样式即 PLAIN 本体）。
        var plainRow = rows.stream()
            .filter(r -> r.size() == 1 && r.get(0).text().startsWith("  first"))
            .findFirst().orElseThrow();
        assertThat(plainRow.get(0).text()).isEqualTo("  first second");
        assertThat(plainRow.get(0).style()).isEqualTo(SemanticColors.plain());
        // 序位行＋条间空行＋无滚动指示（n=2 全可见）。
        var texts = lineTexts(rig.screen());
        assertThat(texts).containsSubsequence("  first second", "  Message 1 of 2", "");
        assertThat(texts).noneMatch(t -> t.matches("  \\(\\d+/\\d+\\)"));
    }

    @Test
    void scrollIndicatorAppearsOnlyWhenWindowIsPartial() {
        // sel=0、n=15 ⇒ endIndex=10<15 ⇒ 指示 "  (1/15)"。
        assertThat(lineTexts(rig(messages(15), "e1", 80).screen()))
            .contains("  (1/15)");
        // 全可见 ⇒ 无指示（pi :68 条件逐字）。
        assertThat(lineTexts(rig(messages(10), null, 80).screen()))
            .noneMatch(t -> t.matches("  \\(\\d+/\\d+\\)"));
    }

    @Test
    void longMessageIsTruncatedToWidthMinusTwoWithEllipsis() {
        var rig = rig(List.of(new AgentSession.ForkMessage("e1",
            "abcdefghijklmnopqrstuvwxyz")), null, 20);
        // width−2=18 ⇒ 15 字符＋"..."。
        assertThat(lineTexts(rig.screen())).contains("› abcdefghijklmno...");
        // 宽字符不越界（列宽纪律）："中" 占 2 列、target=15 ⇒ 7 个（14 列）＋"..."。
        assertThat(UserMessageSelectorScreen.truncateToWidth("中".repeat(10), 18))
            .isEqualTo("中".repeat(7) + "...");
    }

    // ── ⑤ Enter / Esc 结局 ─────────────────────────────────────

    @Test
    void enterSelectsTheHighlightedEntryId() {
        var rig = rig(messages(3), null, 80);
        drive(rig, UP, ENTER);                    // e3 → 上一步 e2 ⇒ 选中 e2
        assertThat(rig.selected().get()).isEqualTo("e2");
        assertThat(rig.cancelled()).isFalse();
    }

    @Test
    void escapeCancelsWithoutSelection() {
        var rig = rig(messages(3), null, 80);
        drive(rig, ESC);
        assertThat(rig.cancelled()).isTrue();
        assertThat(rig.selected().get()).isNull();
    }

    // ── ⑥ 容器头尾（pi :120-130/:143-144）──────────────────────

    @Test
    void containerHeaderAndFooterFrameTheList() {
        var rows = rig(messages(1), null, 80).screen().layout();
        var texts = rows.stream()
            .map(r -> r.stream().map(UserMessageSelectorScreen.Span::text).reduce("", String::concat))
            .toList();
        // 头：空行 ⇒ 粗体标题 ⇒ muted 副标（80 列按 width−2=78 词换行成两行，
        // pi Text paddingX=1）⇒ 空行 ⇒ border ⇒ 空行。
        assertThat(texts.get(0)).isEmpty();
        assertThat(texts.get(1)).isEqualTo(" Fork from Message");
        assertThat(rows.get(1).get(0).style().addModifiers())
            .contains(dev.tamboui.style.Modifier.BOLD);
        assertThat(texts.get(2))
            .isEqualTo(" Select a user message to copy the active path up to that point into a new");
        assertThat(texts.get(3)).isEqualTo(" session");
        assertThat(rows.get(2).get(0).style()).isEqualTo(SemanticColors.muted());
        assertThat(texts.get(4)).isEmpty();
        assertThat(texts.get(5)).isEqualTo("─".repeat(80));
        // 尾：空行 ⇒ border（末两行）。
        assertThat(texts.get(texts.size() - 2)).isEmpty();
        assertThat(texts.getLast()).isEqualTo("─".repeat(80));
    }

    @Test
    void subtitleWordWrapsAtNarrowWidths() {
        // width=40 ⇒ 内容宽 38，副标折三行（pi Text paddingX=1 换行）。
        var texts = lineTexts(rig(messages(1), null, 40).screen());
        assertThat(texts.subList(2, 5)).containsExactly(
            " Select a user message to copy the",
            " active path up to that point into a",
            " new session");
    }

    @Test
    void emptyListShowsTheDefensiveRow() {
        var rig = rig(List.of(), null, 80);
        assertThat(lineTexts(rig.screen())).contains("  No user messages found");
        // 空表上下键 no-op 不炸；Esc 仍可退（pi 组件内 100ms 自动取消不移植，裁决⑥）。
        rig.screen().onKeyEvent(UP);
        rig.screen().onKeyEvent(DOWN);
        drive(rig, ESC);
        assertThat(rig.cancelled()).isTrue();
        assertThat(rig.selected().get()).isNull();
    }
}
