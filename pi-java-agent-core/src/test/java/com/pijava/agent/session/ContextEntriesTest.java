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
        var c2 = compaction("c2", "c1", "new", "c1");
        var path = List.<Entry>of(
            message("a", null, "user", "q"),
            c1,
            message("b", "c1", "assistant", "r"),
            c2,
            message("d", "c2", "user", "q2"));
        var ctx = ContextEntries.contextEntries(path);
        // c2 is authoritative; its firstKept is c1 → pre-portion = [c1, b], then post = [d]
        assertThat(ctx).containsExactly(c2, c1, path.get(2), path.get(4));
        // canonical call form: toMessages on the raw leaf path (splice once)
        var msgs = ContextEntries.toMessages(path);
        assertThat(textOf(msgs.get(0))).contains("new");
        // msgs: [c2-summary, c1-summary, b, d] — c1 appears as a second summary message
        assertThat(textOf(msgs.get(1))).contains("old");
        assertThat(msgs.get(2).role()).isEqualTo("assistant");
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
            new Entry.ActiveToolsChange("atc", 0, "a", Instant.EPOCH, List.of()),
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
}
