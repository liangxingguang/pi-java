package com.pijava.tui.component.tree;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import com.pijava.agent.entry.CustomMessageContent;
import com.pijava.agent.entry.Entry;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.coding.agent.core.SessionTreeBuilder;
import com.pijava.coding.agent.core.SessionTreeNode;
import com.pijava.tui.component.tree.TreeListRenderer.Row;
import com.pijava.tui.component.tree.TreeListRenderer.Span;
import com.pijava.tui.util.TextLayout;

import dev.tamboui.style.Style;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TreeListRenderer} 的渲染规格（docs/19 §6 13–18）：prefix/gutter 形、
 * 文案表全类型、formatToolCall、labelTimestamp 三档、水平视口、帮助行换行。
 */
class TreeListRendererTest {

    private static final Instant T0 = Instant.parse("2026-03-28T14:00:00Z");
    private static final int MAX_VISIBLE = 12;

    // ── 夹具（与 TreeListModelTest 同形）─────────────────────

    private static Entry.Message userMessage(String id, String parentId, String content) {
        return new Entry.Message(id, 0, parentId, T0,
            new Message.UserMessage(List.of(new ContentBlock.TextContent(content)), null), null);
    }

    private static Entry.Message assistantMessage(String id, String parentId, String text) {
        return new Entry.Message(id, 0, parentId, T0,
            new Message.AssistantMessage(List.of(new ContentBlock.TextContent(text)),
                StopReason.STOP, null),
            null);
    }

    private static List<Entry> branchingEntries() {
        return List.of(
            userMessage("user-1", null, "first message"),
            assistantMessage("asst-1", "user-1", "response 1"),
            userMessage("user-2", "asst-1", "second message"),
            assistantMessage("asst-2", "user-2", "response 2"),
            userMessage("user-3a", "asst-2", "branch A start"),
            assistantMessage("asst-3a", "user-3a", "branch A response"),
            userMessage("user-4a", "asst-3a", "branch A deep"),
            assistantMessage("asst-4a", "user-4a", "branch A leaf"),
            userMessage("user-3b", "asst-2", "branch B start"),
            assistantMessage("asst-3b", "user-3b", "branch B response"),
            userMessage("user-4b", "asst-3b", "branch B deep"));
    }

    private static TreeListModel model(List<? extends Entry> entries, String currentLeafId) {
        return new TreeListModel(
            SessionTreeBuilder.build(entries, id -> null, id -> null),
            currentLeafId, MAX_VISIBLE, null, null);
    }

    private static String lineText(Row row) {
        return spansText(row.gutter()) + spansText(row.body());
    }

    private static String spansText(List<Span> spans) {
        var sb = new StringBuilder();
        spans.forEach(s -> sb.append(s.text()));
        return sb.toString();
    }

    private static Row rowOf(List<Row> rows, String entryId) {
        return rows.stream().filter(r -> r.entryId().equals(entryId)).findFirst()
            .orElseThrow(() -> new AssertionError("no row for " + entryId));
    }

    // ── 13：prefix/gutter/折叠符/活动路径形 ─────────────────────

    @Test
    void prefixGutterAndFoldMarkerShapes() {
        var m = model(branchingEntries(), "asst-4a");
        var rows = new TreeListRenderer().buildRows(m);

        // 分支点两子：├⊟／└⊟（可折 ⇒ ⊟）。
        assertThat(lineText(rowOf(rows, "user-3a"))).contains("├⊟");
        assertThat(lineText(rowOf(rows, "user-3b"))).contains("└⊟");
        // 分支内单链：祖先 gutter 延续 │。
        assertThat(lineText(rowOf(rows, "asst-3a"))).contains("│");
        assertThat(lineText(rowOf(rows, "user-4b"))).doesNotContain("│");
        // 活动路径（根→asst-4a）行首 •；非活动分支无。
        assertThat(lineText(rowOf(rows, "user-1"))).contains("•");
        assertThat(lineText(rowOf(rows, "asst-4a"))).contains("•");
        assertThat(lineText(rowOf(rows, "user-3b"))).doesNotContain("•");
        // 选中行（当前叶 asst-4a）游标 ›。
        assertThat(spansText(rowOf(rows, "asst-4a").gutter())).isEqualTo("› ");
        assertThat(spansText(rowOf(rows, "user-1").gutter())).isEqualTo("  ");

        // 折叠 user-3a ⇒ ⊞；折叠根（无 connector）⇒ 独立 ⊞ 标记。
        while (!"user-3a".equals(m.selectedEntryId())) {
            m.moveUp();
        }
        m.foldOrUp();
        var folded = new TreeListRenderer().buildRows(m);
        assertThat(lineText(rowOf(folded, "user-3a"))).contains("├⊞");
        while (!"user-1".equals(m.selectedEntryId())) {
            m.foldOrUp();
        }
        m.foldOrUp(); // 折 user-1（根，无 connector）
        var rootFolded = new TreeListRenderer().buildRows(m);
        assertThat(lineText(rowOf(rootFolded, "user-1"))).contains("⊞ ");
    }

    // ── 14：文案表全类型（§1.4 表逐行）─────────────────────────

    @Test
    void displayTextCoversAllEntryTypes() {
        var longError = "E".repeat(100);
        var entries = List.of(
            userMessage("u1", null, "hello\nworld"),
            assistantMessage("a1", "u1", "hi"),
            new Entry.Message("a-abort", 0, "a1", T0,
                new Message.AssistantMessage(List.of(), StopReason.ABORTED, null), null),
            new Entry.Message("a-err", 0, "a-abort", T0,
                new Message.AssistantMessage(List.of(), StopReason.ERROR, null,
                    null, null, null, null, null, null, null, null, null,
                    longError, null, null), null),
            new Entry.Message("a-empty", 0, "a-err", T0,
                new Message.AssistantMessage(List.of(), StopReason.STOP, null), null),
            new Entry.Message("a-call", 0, "a-empty", T0,
                new Message.AssistantMessage(List.of(new ContentBlock.ToolUseContent(
                    "tc-1", "read", Map.of("path", "test.ts"))), StopReason.TOOL_USE, null), null),
            new Entry.Message("r1", 0, "a-call", T0,
                new Message.ToolResultMessage("tc-1", "read",
                    List.of(new ContentBlock.TextContent("body")), false), null),
            new Entry.Message("r2", 0, "r1", T0,
                new Message.ToolResultMessage("tc-x", "mystery",
                    List.of(new ContentBlock.TextContent("body")), false), null),
            new Entry.BashExecution("b1", 0, "r2", T0, "ls -la", "", 0, false, false,
                null, null),
            new Entry.CustomMessage("cm1", 0, "b1", T0, "notice",
                CustomMessageContent.of("hey there"), true, null),
            new Entry.Compaction("c1", 0, "cm1", T0, "summary text", null, List.of(),
                12345, null, null, null),
            new Entry.BranchSummary("bs1", 0, "c1", T0, "u1", "explored", null, null, null),
            new Entry.ModelChange("mc1", 0, "bs1", T0, "anthropic", "claude-sonnet-4"),
            new Entry.ThinkingLevelChange("tl1", 0, "mc1", T0, "high"),
            new Entry.Custom("cu1", 0, "tl1", T0, "evt", null),
            new Entry.ContextEdit("ce1", 0, "cu1", T0, "a1", null),
            new Entry.ContextEdit("ce2", 0, "ce1", T0, "a2",
                new Entry.ContextEdit.Replacement(CustomMessageContent.of("x"))));
        // 17 条全类型 ⇒ 视口要全纳（窗口=maxVisibleLines）。
        // 当前叶＝a-empty：无文本非错误 assistant 仅当前叶恒显（pi :346）。
        var m = new TreeListModel(
            SessionTreeBuilder.build(entries, id -> null, id -> null),
            "a-empty", 50, null, FilterMode.ALL);
        var rows = new TreeListRenderer().buildRows(m);

        assertThat(lineText(rowOf(rows, "u1"))).contains("user: hello world");
        assertThat(lineText(rowOf(rows, "a1"))).contains("assistant: hi");
        assertThat(lineText(rowOf(rows, "a-abort"))).contains("(aborted)");
        assertThat(lineText(rowOf(rows, "a-err")))
            .contains("E".repeat(80)).doesNotContain("E".repeat(81));
        assertThat(lineText(rowOf(rows, "a-empty"))).contains("(no content)");
        assertThat(lineText(rowOf(rows, "r1"))).contains("[read: test.ts]");
        assertThat(lineText(rowOf(rows, "r2"))).contains("[mystery]");
        assertThat(lineText(rowOf(rows, "b1"))).contains("[bash]: ls -la");
        assertThat(lineText(rowOf(rows, "cm1"))).contains("[notice]: hey there");
        assertThat(lineText(rowOf(rows, "c1"))).contains("[compaction: 12k tokens]");
        assertThat(lineText(rowOf(rows, "bs1"))).contains("[branch summary]: explored");
        assertThat(lineText(rowOf(rows, "mc1"))).contains("[model: claude-sonnet-4]");
        assertThat(lineText(rowOf(rows, "tl1"))).contains("[thinking: high]");
        assertThat(lineText(rowOf(rows, "cu1"))).contains("[custom: evt]");
        assertThat(lineText(rowOf(rows, "ce1"))).contains("[context omit: a1]");
        assertThat(lineText(rowOf(rows, "ce2"))).contains("[context replace: a2]");
    }

    // ── 15：formatToolCall 七型＋home 缩写＋截断 ────────────────

    @Test
    void formatToolCallAllShapes() {
        assertThat(TreeListRenderer.formatToolCall("read", Map.of("path", "a.ts")))
            .isEqualTo("[read: a.ts]");
        assertThat(TreeListRenderer.formatToolCall("read",
                Map.of("path", "a.ts", "offset", 5, "limit", 10)))
            .isEqualTo("[read: a.ts:5-14]");
        assertThat(TreeListRenderer.formatToolCall("read", Map.of("path", "a.ts", "limit", 10)))
            .isEqualTo("[read: a.ts:1-10]");
        assertThat(TreeListRenderer.formatToolCall("read", Map.of("path", "a.ts", "offset", 5)))
            .isEqualTo("[read: a.ts:5]");
        assertThat(TreeListRenderer.formatToolCall("write", Map.of("path", "w.ts")))
            .isEqualTo("[write: w.ts]");
        assertThat(TreeListRenderer.formatToolCall("edit", Map.of("file_path", "e.ts")))
            .isEqualTo("[edit: e.ts]");
        assertThat(TreeListRenderer.formatToolCall("grep",
                Map.of("pattern", "foo", "path", "src")))
            .isEqualTo("[grep: /foo/ in src]");
        assertThat(TreeListRenderer.formatToolCall("find", Map.of("pattern", "*.ts")))
            .isEqualTo("[find: *.ts in .]");
        assertThat(TreeListRenderer.formatToolCall("ls", Map.of()))
            .isEqualTo("[ls: .]");
        // bash：归一化后 50 截，省略号看**原始**长度（pi :975-981）。
        var longCmd = "x".repeat(60);
        assertThat(TreeListRenderer.formatToolCall("bash", Map.of("command", longCmd)))
            .isEqualTo("[bash: " + "x".repeat(50) + "...]");
        assertThat(TreeListRenderer.formatToolCall("bash", Map.of("command", "echo hi")))
            .isEqualTo("[bash: echo hi]");
        // 自定义工具：JSON 40 截。
        var custom = TreeListRenderer.formatToolCall("mytool",
            Map.of("alpha", "1234567890123456789012345678901234567890"));
        assertThat(custom).startsWith("[mytool: {");
        assertThat(custom).endsWith("...]");

        // home ⇒ ~ 缩写（pi :947-951，HOME 或 USERPROFILE）。
        String home = System.getenv("HOME");
        if (home == null || home.isEmpty()) {
            home = System.getenv("USERPROFILE");
        }
        if (home != null && !home.isEmpty()) {
            assertThat(TreeListRenderer.formatToolCall("read", Map.of("path", home + "/x.ts")))
                .isEqualTo("[read: ~/x.ts]");
        }
    }

    // ── 16：labelTimestamp 三档（注入时钟）─────────────────────

    @Test
    void labelTimestampThreeTiers() {
        var clock = Clock.fixed(Instant.parse("2026-10-21T15:00:00Z"), ZoneOffset.UTC);
        var renderer = new TreeListRenderer(clock);
        // 当天 ⇒ HH:MM。
        assertThat(renderer.formatLabelTimestamp(Instant.parse("2026-10-21T08:32:00Z")))
            .isEqualTo("08:32");
        // 今年 ⇒ M/D HH:MM（pi :336 用例的 "3/28 14:32"）。
        assertThat(renderer.formatLabelTimestamp(Instant.parse("2026-03-28T14:32:00Z")))
            .isEqualTo("3/28 14:32");
        // 跨年 ⇒ YY/M/D HH:MM。
        assertThat(renderer.formatLabelTimestamp(Instant.parse("2025-03-28T14:32:00Z")))
            .isEqualTo("25/3/28 14:32");

        // shift+t 后行内出现时间戳＋状态标（pi :336 全链）。
        var tree = SessionTreeBuilder.build(
            List.of(userMessage("u1", null, "hello"), assistantMessage("a1", "u1", "hi")),
            id -> "u1".equals(id) ? "checkpoint" : null,
            id -> "u1".equals(id) ? Instant.parse("2026-03-28T14:32:00Z") : null);
        var m = new TreeListModel(tree, "a1", MAX_VISIBLE, null, null);
        var off = renderer.buildRows(m);
        assertThat(lineText(rowOf(off, "u1"))).contains("[checkpoint]")
            .doesNotContain("3/28 14:32");
        m.toggleLabelTimestamps();
        var on = renderer.buildRows(m);
        assertThat(lineText(rowOf(on, "u1"))).contains("3/28 14:32");
        assertThat(renderer.statusLine(m)).contains("[+label time]");
    }

    // ── 17：水平视口（pi :62-103 数值例）───────────────────────

    @Test
    void horizontalViewportShiftsBodyNotGutter() {
        var renderer = new TreeListRenderer();
        String bodyText = "prefix-part• " + "B".repeat(88);   // 宽 100
        var rows = List.of(
            new Row(List.of(new Span("› ", Style.EMPTY)),
                List.of(new Span(bodyText, Style.EMPTY)),
                90, 100, true, "sel"),
            new Row(List.of(new Span("  ", Style.EMPTY)),
                List.of(new Span("short", Style.EMPTY)),
                5, 5, false, "other"));
        var lines = renderer.viewport(rows, 30);

        // 选中行锚点 90 > 28-9 ⇒ 滚动：gutter 不动、body 左移、无省略号、总宽 ≤30。
        var selected = spansText(lines.get(0));
        assertThat(selected).startsWith("› ");
        assertThat(selected).doesNotContain("...");
        assertThat(selected).doesNotContain("prefix-part");  // 锚点左缘已滚出
        assertThat(TextLayout.displayWidth(selected)).isLessThanOrEqualTo(30);
        // 未选中行同样被切（视口是全局滚动），gutter 保留。
        var other = spansText(lines.get(1));
        assertThat(other).startsWith("  ");
        assertThat(TextLayout.displayWidth(other)).isLessThanOrEqualTo(30);
    }

    @Test
    void noScrollWhenAnchorFits() {
        var renderer = new TreeListRenderer();
        var rows = List.of(
            new Row(List.of(new Span("› ", Style.EMPTY)),
                List.of(new Span("body", Style.EMPTY)), 2, 4, true, "sel"));
        var lines = renderer.viewport(rows, 30);
        assertThat(spansText(lines.get(0))).isEqualTo("› body");
    }

    // ── 18：帮助行窄宽换行不截断（pi :290）─────────────────────

    @Test
    void helpLineWrapsWithoutTruncationAtWidth30() {
        var lines = TreeHelpLine.render(30);
        var joined = String.join("\n", lines);
        assertThat(joined).contains("branch").contains("copy").contains("filters")
            .contains("cycle").contains("label time").contains("move").contains("page")
            .contains("label");
        assertThat(joined).doesNotContain("...");
        assertThat(lines).allMatch(l -> TextLayout.displayWidth(l) <= 30);
    }

    // ── 状态行/空态（pi :672-676/:763-768）────────────────────

    @Test
    void statusLineAndEmptyState() {
        var m = model(branchingEntries(), "asst-4a");
        var renderer = new TreeListRenderer();
        assertThat(renderer.statusLine(m))
            .isEqualTo("  (" + (m.selectedIndex() + 1) + "/" + m.filteredNodes().size() + ")");
        m.setFilter(FilterMode.USER_ONLY);
        assertThat(renderer.statusLine(m)).contains("[user]");

        var empty = new TreeListModel(List.of(), null, MAX_VISIBLE, null, null);
        assertThat(renderer.statusLine(empty)).isEqualTo("  (0/0)");
        assertThat(TreeListRenderer.emptyLine()).isEqualTo("  No entries found");
    }
}
