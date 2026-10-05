package com.pijava.agent.session;

import java.time.Instant;
import java.util.List;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 压缩感知上下文构建（对齐 pi {@code buildSessionPath} + {@code buildContextEntries}
 * + {@code sessionEntryToContextMessages}）：叶子路径裁剪、最新 compaction 胜出、
 * summary → user 消息转换（逐字节复刻 pi 前缀/后缀）。
 */
class ContextEntriesTest {

    // ── fixtures ─────────────────────────────────────────────────────────

    private static Entry.Message message(String id, String parentId, String role, String text) {
        var block = new ContentBlock.TextContent(text);
        var msg = switch (role) {
            case "user" -> (com.pijava.ai.message.Message) new Message.UserMessage(List.of(block));
            case "assistant" -> new Message.AssistantMessage(List.of(block));
            default -> new Message.ToolResultMessage("call-1", "bash", List.of(block), false);
        };
        return new Entry.Message(id, 0, parentId, Instant.EPOCH, msg, null);
    }

    /** Assistant message entry carrying an explicit stop reason (D4 provenance). */
    private static Entry.Message assistantEntry(String id, String parentId, String text,
                                               String stopReason) {
        var msg = new Message.AssistantMessage(List.of(new ContentBlock.TextContent(text)),
            stopReason, null);
        return new Entry.Message(id, 0, parentId, Instant.EPOCH, msg, null);
    }

    private static Entry.Compaction compaction(String id, String parentId, String summary, String firstKeptId) {
        return new Entry.Compaction(id, 0, parentId, Instant.EPOCH, summary,
            firstKeptId, List.of(), 1000, null, null);
    }

    private static Entry.BranchSummary branchSummary(String id, String parentId, String summary) {
        return new Entry.BranchSummary(id, 0, parentId, Instant.EPOCH, "from-1", summary,
            java.util.Map.of(), null);
    }

    private static Entry.ModelChange modelChange(String id, String parentId) {
        return new Entry.ModelChange(id, 0, parentId, Instant.EPOCH, "p", "m");
    }

    private static String textOf(Message m) {
        if (m instanceof Message.UserMessage u) {
            return ((ContentBlock.TextContent) u.content().get(0)).text();
        }
        return null;
    }

    // ── tests ────────────────────────────────────────────────────────────

    @Test
    void noCompactionPassthrough() {
        var path = List.<Entry>of(
            message("a", null, "user", "hi"),
            message("b", "a", "assistant", "hello"));
        assertThat(ContextEntries.contextEntries(path)).isEqualTo(path);
        var msgs = ContextEntries.toMessages(path);
        assertThat(msgs).hasSize(2);
        assertThat(msgs.get(0).role()).isEqualTo("user");
        assertThat(msgs.get(1).role()).isEqualTo("assistant");
    }

    @Test
    void compactionYieldsSummaryThenKept() {
        var comp = compaction("c", "b", "SUMMARY-TEXT", "b");
        var path = List.<Entry>of(
            message("a", null, "user", "q1"),
            message("b", "a", "assistant", "r1"),
            comp,
            message("d", "c", "user", "q2"));
        var ctx = ContextEntries.contextEntries(path);
        assertThat(ctx).containsExactly(comp, path.get(1), path.get(3));
        var msgs = ContextEntries.toMessages(ctx);
        assertThat(msgs).hasSize(3);
        assertThat(textOf(msgs.get(0))).isEqualTo(
            "The conversation history before this point was compacted into the following summary:\n\n"
                + "<summary>\nSUMMARY-TEXT\n</summary>");
    }

    @Test
    void latestOfTwoCompactionsWins() {
        var c1 = compaction("c1", "a", "old", "a");
        var c2 = compaction("c2", "b", "new", "c1");
        var path = List.<Entry>of(
            message("a", null, "user", "q"),
            c1,
            message("b", "c1", "assistant", "r"),
            c2,
            message("d", "c2", "user", "q2"));
        var ctx = ContextEntries.contextEntries(path);
        // c2 is authoritative; its firstKept is c1 → pre-portion = [c1, b], then post = [d]
        assertThat(ctx).containsExactly(c2, c1, path.get(2), path.get(4));
        // toMessages on the raw leaf path (splice once)
        var msgs = ContextEntries.toMessages(path);
        assertThat(textOf(msgs.get(0))).contains("new");
        // pi 锚点：index>0 的 compaction（c1）投影为空 —— raw 保留、不产消息，
        // 所以投影是 [c2-summary, b assistant, d user]（旧「c1 出第二条摘要」
        // 的断言是本仓发明，已撤）。
        assertThat(msgs).hasSize(3);
        assertThat(msgs.get(1).role()).isEqualTo("assistant");
        assertThat(msgs.get(2).role()).isEqualTo("user");
    }

    @Test
    void firstKeptEntryIdMissingFallsBack() {
        // firstKept id never matches any path entry → pre-portion empty
        var comp = compaction("c", "b", "s", "no-such-id");
        var path = List.<Entry>of(
            message("a", null, "user", "q1"),
            message("b", "a", "assistant", "r1"),
            comp,
            message("d", "c", "user", "q2"));
        assertThat(ContextEntries.contextEntries(path)).containsExactly(comp, path.get(3));
    }

    @Test
    void branchSummaryConvertsToUserMessageAndNullSkipped() {
        var bs = branchSummary("bs", "a", "BRANCH-TEXT");
        var path = List.<Entry>of(
            message("a", null, "user", "q"),
            bs,
            new Entry.BranchSummary("bs2", 0, "bs", Instant.EPOCH, "from-2", null, java.util.Map.of(), null));
        var msgs = ContextEntries.toMessages(path);
        assertThat(msgs).hasSize(2);
        assertThat(textOf(msgs.get(1))).isEqualTo(
            "The following is a summary of a branch that this conversation came back from:\n\n"
                + "<summary>\nBRANCH-TEXT</summary>");
    }

    @Test
    void nonMessageEntryTypesSkipped() {
        var path = List.<Entry>of(
            message("a", null, "user", "q"),
            modelChange("mc", "a"),
            new Entry.ThinkingLevelChange("tlc", 0, "a", Instant.EPOCH, "high"),
            new Entry.Custom("c", 0, "a", Instant.EPOCH, "type", java.util.Map.of()));
        var msgs = ContextEntries.toMessages(path);
        assertThat(msgs).hasSize(1);
        assertThat(msgs.get(0).role()).isEqualTo("user");
    }

    @Test
    void customMessageTextBecomesUserMessageIgnoringDisplayAndDetails() {
        var cm = new Entry.CustomMessage("cm", 0, "a", Instant.EPOCH, "ext",
            com.pijava.agent.entry.CustomMessageContent.of("injected"), false,
            java.util.Map.of("secret", "not-for-llm"));
        var path = List.<Entry>of(
            message("a", null, "user", "q"),
            cm);
        var msgs = ContextEntries.toMessages(path);
        assertThat(msgs).hasSize(2);
        assertThat(msgs.get(1)).isInstanceOf(Message.UserMessage.class);
        assertThat(((Message.UserMessage) msgs.get(1)).content())
            .containsExactly(new ContentBlock.TextContent("injected"));
    }

    @Test
    void customMessageBlocksPassThroughToUserMessage() {
        var blocks = List.<ContentBlock>of(
            new ContentBlock.TextContent("look"),
            new ContentBlock.ImageContent("image/png", "aGVsbG8="));
        var cm = new Entry.CustomMessage("cm", 0, "a", Instant.EPOCH, "ext",
            com.pijava.agent.entry.CustomMessageContent.of(blocks), true, null);
        var msgs = ContextEntries.toMessages(List.<Entry>of(
            message("a", null, "user", "q"), cm));
        assertThat(msgs).hasSize(2);
        assertThat(((Message.UserMessage) msgs.get(1)).content()).isEqualTo(blocks);
    }

    @Test
    void customEntryRemainsSkippedButCustomMessageProjects() {
        var path = List.<Entry>of(
            new Entry.Custom("c", 0, null, Instant.EPOCH, "type", java.util.Map.of()),
            new Entry.CustomMessage("cm", 1, "c", Instant.EPOCH, "type",
                com.pijava.agent.entry.CustomMessageContent.of("visible to llm"), true, null));
        var msgs = ContextEntries.toMessages(path);
        assertThat(msgs).hasSize(1);
        assertThat(textOf(msgs.get(0))).isEqualTo("visible to llm");
    }

    @Test
    void missingParentStopsWalk() {
        // compaction subset: "a" absent, "b".parentId dangles — walk must not throw
        var entries = List.<Entry>of(
            message("b", "missing", "assistant", "r"),
            compaction("c", "b", "s", "b"));
        var leafPath = ContextEntries.pathToLeaf(entries, "c");
        assertThat(leafPath).hasSize(2);
        assertThat(ContextEntries.toMessages(leafPath)).hasSize(2);
    }

    @Test
    void leafIdSelectsBranch() {
        var common = message("a", null, "user", "q");
        var sib1 = message("b1", "a", "assistant", "fork-one");
        var sib2 = message("b2", "a", "assistant", "fork-two");
        var entries = List.<Entry>of(common, sib1, sib2);
        var leafPath = ContextEntries.pathToLeaf(entries, "b2");
        assertThat(leafPath).containsExactly(common, sib2);
    }

    @Test
    void nullLeafIdUsesLastEntry() {
        var a = message("a", null, "user", "q");
        var b = message("b", "a", "assistant", "r");
        var entries = List.<Entry>of(a, b);
        assertThat(ContextEntries.pathToLeaf(entries, null)).containsExactly(a, b);
    }

    // ── D4: 投影规则（stopReason 决定 assistant entry 是否进 provider 上下文）────────

    @Test
    void deferredErrorAndAbortedAssistantMessagesProjectToNothing() {
        for (String stopReason : List.of("deferred", "error", "aborted")) {
            var assistant = assistantEntry("a-" + stopReason, "u-1", "partial text", stopReason);
            var messages = ContextEntries.toMessages(List.<Entry>of(
                message("u-1", null, "user", "hello"), assistant));

            assertThat(messages)
                .as("stopReason=%s 的 assistant 消息必须投影为零条（pi spec docs/harness-v2.md:164）", stopReason)
                .extracting(Message::role)
                .containsExactly("user");
        }
    }

    @Test
    void completedToolUseAndLengthMessagesStillProject() {
        for (String stopReason : List.of("stop", "toolUse", "length")) {
            var assistant = assistantEntry("a-" + stopReason, null, "answer", stopReason);

            assertThat(ContextEntries.toMessages(List.<Entry>of(assistant)))
                .as("stopReason=%s 不属于被投影掉的集合", stopReason)
                .hasSize(1);
        }
    }

    @Test
    void assistantWithoutStopReasonStillProjects() {
        // 旧数据 / 非流式构造的消息 stopReason 为 null，必须保留。
        var assistant = (Entry.Message) new Entry.Message("a-null", 0, null, null,
            new Message.AssistantMessage(List.of(new ContentBlock.TextContent("answer"))), null);

        assertThat(ContextEntries.toMessages(List.<Entry>of(assistant))).hasSize(1);
    }

    // ── A1: 系统消息载荷（docs/08 §A1）─────────────────────────────────────

    /**
     * 系统消息是第四种 {@code Message} 变体，仍然以 {@code Entry.Message} 载荷的形式
     * 存在于路径中（A1 **不**新增 {@code Entry.SystemMessage} 子类型）。投影必须原样、
     * 原位带过 —— 既不能被 {@code project} 的 stopReason 过滤吃掉（它只看 assistant），
     * 也不能被挪到首位或末位。
     */
    @Test
    void systemMessagePayloadSurvivesProjectionInPlace() {
        var system = new Message.SystemMessage(
            "changed", Instant.ofEpochMilli(7), java.util.Map.of(), List.of(), List.of());
        var before = message("u-1", null, "user", "before");
        var after = message("u-2", "s-1", "user", "after");
        var systemEntry = new Entry.Message("s-1", 0, "u-1", Instant.ofEpochMilli(7), system, null);

        var messages = ContextEntries.toMessages(List.<Entry>of(before, systemEntry, after));

        assertThat(messages).containsExactly(
            new Message.UserMessage(List.of(new ContentBlock.TextContent("before"))),
            system,
            new Message.UserMessage(List.of(new ContentBlock.TextContent("after"))));
    }

    /**
     * 工具增删**不是** entry（包 A3 的裁决 R2，{@code 原 docs/51 §9}）：{@code Entry.ActiveToolsChange}
     * 已删，这条线走的是系统消息的 {@code toolsAdded}/{@code toolsRemoved} ⇒ 由
     * {@code Message.SystemMessage} 那一支承载（见 {@code toolsAdded} 相关用例）。
     */
    @Test
    void toolStateChangesRideOnSystemMessagesNotEntries() {
        var change = new Message.SystemMessage("", Instant.EPOCH, java.util.Map.of(),
            List.of(new com.pijava.ai.api.ToolDefinition("bash", "run", java.util.Map.of())),
            List.of());

        var messages = ContextEntries.toMessages(List.<Entry>of(
            new Entry.Message("t-1", 0, null, Instant.EPOCH, change, null)));

        assertThat(messages).containsExactly(change);
    }

    // ── D3: context_edit 投影（docs/13 §4.3；pi projectContextEntry）────────────

    private static Entry.ContextEdit omission(String id, String parentId, String targetId) {
        return new Entry.ContextEdit(id, 0, parentId, Instant.EPOCH, targetId, null);
    }

    private static Entry.ContextEdit replacement(String id, String parentId, String targetId,
                                                  com.pijava.agent.entry.CustomMessageContent content) {
        return new Entry.ContextEdit(id, 0, parentId, Instant.EPOCH, targetId,
            new Entry.ContextEdit.Replacement(content));
    }

    @Test
    void omitsTheTargetFromProjectedMessages() {
        var userTarget = message("u", null, "user", "user text");
        var assistantTarget = message("a", "u", "assistant", "assistant text");
        var toolTarget = message("t", "a", "tool", "tool text");
        var path = List.<Entry>of(userTarget, assistantTarget, toolTarget,
            omission("e1", "t", "u"),
            omission("e2", "e1", "a"),
            omission("e3", "e2", "t"));

        assertThat(ContextEntries.toMessages(path))
            .as("omit 编辑须把 user/assistant/tool 目标全部移出投影；edit 自身不产消息")
            .isEmpty();
    }

    @Test
    void replacesOnlyContentAndKeepsMetadata() {
        var usage = new com.pijava.ai.Usage(10, 1, 0, 0, null, null, 11,
            com.pijava.ai.Usage.Cost.zero());
        var timestamp = Instant.ofEpochMilli(42);
        var assistant = new Message.AssistantMessage(
            List.of(new ContentBlock.TextContent("original")), "stop", null,
            "faux", "faux", "faux", usage, timestamp, null, null);
        var path = List.<Entry>of(
            new Entry.Message("a", 0, null, timestamp, assistant, null),
            replacement("e1", "a", "a", com.pijava.agent.entry.CustomMessageContent.of(
                List.of(new ContentBlock.TextContent("replaced")))));

        var msgs = ContextEntries.toMessages(path);
        assertThat(msgs).hasSize(1);
        assertThat(msgs.get(0)).isInstanceOf(Message.AssistantMessage.class);
        var projected = (Message.AssistantMessage) msgs.get(0);
        assertThat(((ContentBlock.TextContent) projected.content().get(0)).text())
            .isEqualTo("replaced");
        assertThat(projected.usage())
            .as("只换 content：usage 元数据保留（pi projectContextEntry 只覆盖 content）")
            .isSameAs(usage);
        assertThat(projected.timestamp()).isSameAs(timestamp);
        assertThat(projected.stopReason()).isEqualTo("stop");
    }

    @Test
    void letsTheLatestEditWin() {
        var target = message("a", null, "assistant", "original");
        var path = List.<Entry>of(target,
            replacement("e1", "a", "a", com.pijava.agent.entry.CustomMessageContent.of("first")),
            omission("e2", "e1", "a"),
            replacement("e3", "e2", "a", com.pijava.agent.entry.CustomMessageContent.of("restored")));

        var msgs = ContextEntries.toMessages(path);
        assertThat(msgs).hasSize(1);
        assertThat(((ContentBlock.TextContent) ((Message.AssistantMessage) msgs.get(0))
            .content().get(0)).text())
            .as("同目标多次编辑：Map.set ⇒ 后者胜（pi buildSessionProjection :551-554）")
            .isEqualTo("restored");
    }

    @Test
    void normalizesImportedStringContentForAssistantAndTool() {
        var assistantTarget = message("a", null, "assistant", "original");
        var toolTarget = message("t", "a", "tool", "original result");
        var path = List.<Entry>of(assistantTarget, toolTarget,
            replacement("e1", "t", "a", com.pijava.agent.entry.CustomMessageContent.of(
                "assistant replacement")),
            replacement("e2", "e1", "t", com.pijava.agent.entry.CustomMessageContent.of(
                "result replacement")));

        var msgs = ContextEntries.toMessages(path);
        assertThat(msgs).hasSize(2);
        assertThat(msgs.get(0))
            .as("assistant content 不接受裸串 ⇒ 归一成 text 块数组（pi :533-536 导入兜底）")
            .isEqualTo(new Message.AssistantMessage(
                List.of(new ContentBlock.TextContent("assistant replacement"))));
        assertThat(msgs.get(1)).isEqualTo(new Message.ToolResultMessage(
            "call-1", "bash",
            List.of(new ContentBlock.TextContent("result replacement")), false));
    }

    @Test
    void ignoresAnEditWhoseTargetIsNotProjected() {
        var path = List.<Entry>of(
            message("a", null, "user", "q"),
            omission("e1", "a", "no-such-target"));

        var msgs = ContextEntries.toMessages(path);
        assertThat(msgs)
            .as("孤儿 edit：目标不在投影里 ⇒ 静默忽略，不影响其它消息")
            .hasSize(1);
        assertThat(msgs.get(0).role()).isEqualTo("user");
    }

    @Test
    void appliesAPostCompactionEditToARetainedEntry() {
        var summarized = message("s", null, "user", "summarized");
        var retained = message("r", "s", "user", "original retained");
        var comp = compaction("c", "r", "summary", "r");
        var edit = replacement("e1", "c", "r",
            com.pijava.agent.entry.CustomMessageContent.of("edited retained"));
        var path = List.<Entry>of(summarized, retained, comp, edit);

        var msgs = ContextEntries.toMessages(path);
        assertThat(msgs).hasSize(2);
        assertThat(textOf(msgs.get(0))).contains("summary");
        assertThat(textOf(msgs.get(1)))
            .as("压缩后 edit 命中保留条目（pi session-context-edit 测试同名场景）")
            .isEqualTo("edited retained");
    }
}
