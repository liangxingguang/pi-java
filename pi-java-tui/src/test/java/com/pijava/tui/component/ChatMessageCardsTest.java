package com.pijava.tui.component;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.pijava.agent.entry.Entry;
import com.pijava.coding.agent.extension.MarkdownTransformContext;
import com.pijava.coding.agent.extension.MarkdownTransformer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L7 W2（docs/23 §4.2/§4.4）：compaction/branch/skill 三消息卡＋bash 卡——
 * 投影、collapsed/expanded 文案逐字、变换链接线（漏项#6：messageId/timestamp
 * 到达 context）、{@link SkillBlocks} 正则逐字。
 */
class ChatMessageCardsTest {

    @AfterEach
    void tearDown() {
        MarkdownTransformers.install(null);
        MessageBubbleVisibility.installToolsExpanded(null);
    }

    private static List<String> markups(ChatMessage msg) {
        return MessageBubble.lines(msg).stream().map(LogicalLine::markup).toList();
    }

    // ── 投影 ──────────────────────────────────────────────────────────

    /** R19：user 消息命中 {@code <skill>} 块 ⇒ SkillCard（id/ts 来自持久化条目）。 */
    @Test
    void userMessageWithSkillBlockProjectsCard() {
        var text = "<skill name=\"commit\" location=\"/tmp/skills/commit.md\">\nbody line\n</skill>\n\nplease commit";
        var entry = new Entry.Message("m-1", 0, null,
            java.time.Instant.parse("2026-11-23T10:00:00Z"),
            new com.pijava.ai.message.Message.UserMessage(
                List.of(new com.pijava.ai.message.ContentBlock.TextContent(text)), null),
            null);

        assertThat(ChatMessage.from(entry)).isEqualTo(new ChatMessage.SkillCard(
            "commit", "body line", "please commit", "m-1", "2026-11-23T10:00:00Z"));
    }

    /** R19：无 userMessage 尾随 ⇒ null；非 skill 文本 ⇒ 原臂。 */
    @Test
    void skillParseEdgeCases() {
        assertThat(SkillBlocks.parse("<skill name=\"a\" location=\"l\">\nx\n</skill>"))
            .isEqualTo(new SkillBlocks.SkillBlock("a", "l", "x", null));
        assertThat(SkillBlocks.parse("plain text")).isNull();
        assertThat(SkillBlocks.parse("<skill name=\"a\" location=\"l\">\nx\n</skill>\n\n  \n"))
            .isEqualTo(new SkillBlocks.SkillBlock("a", "l", "x", null)); // pi：userMessage trim 空 ⇒ undefined
    }

    // ── collapsed/expanded 文案 ────────────────────────────────────────

    /** R17 collapsed（pi 逐字）：label 行＋`Compacted from N tokens (ctrl+o to expand)`。 */
    @Test
    void compactionCardCollapsedText() {
        var lines = markups(new ChatMessage.CompactionCard(1000, "summary body", "c", null));
        assertThat(lines).containsExactly("[bold][[compaction]][/]",
            "Compacted from 1,000 tokens ([dim]ctrl+o[/] to expand)");
    }

    /** R17 expanded：label 行＋「**Compacted from N tokens**\n\n」头＋正文。 */
    @Test
    void compactionCardExpandedText() {
        MessageBubbleVisibility.installToolsExpanded(() -> true);
        var lines = markups(new ChatMessage.CompactionCard(12, "kept msgs", "c", null));
        assertThat(lines.get(0)).isEqualTo("[bold][[compaction]][/]");
        assertThat(lines).anyMatch(m -> m.contains("Compacted from 12 tokens"));
        assertThat(lines).anyMatch(m -> m.contains("kept msgs"));
    }

    /** R18 collapsed（pi 逐字）。 */
    @Test
    void branchCardCollapsedText() {
        var lines = markups(new ChatMessage.BranchCard("summ", "b", null));
        assertThat(lines).containsExactly("[bold][[branch]][/]",
            "Branch summary ([dim]ctrl+o[/] to expand)");
    }

    /** R19 collapsed 单行（pi 逐字）＋ userMessage 追加 User 气泡。 */
    @Test
    void skillCardCollapsedAndUserMessage() {
        var lines = markups(new ChatMessage.SkillCard("commit", "body", "please commit", null, null));
        assertThat(lines.get(0))
            .isEqualTo("[bold][[skill]][/] commit ([dim]ctrl+o[/] to expand)");
        assertThat(lines.get(1)).startsWith("› ");
        assertThat(lines.get(1)).contains("please commit");
    }

    // ── 变换链接线（漏项#6）───────────────────────────────────────────

    /** compaction/branch 走 SUMMARY 型且带 id/ts；skill 走 CUSTOM 型。 */
    @Test
    void transformersReceiveTypeAndIdentity() {
        var seen = new AtomicReference<MarkdownTransformContext>();
        MarkdownTransformer spy = (markdown, context) -> {
            seen.set(context);
            return markdown;
        };
        MarkdownTransformers.install(() -> List.of(spy));
        MessageBubbleVisibility.installToolsExpanded(() -> true);

        MessageBubble.lines(new ChatMessage.CompactionCard(5, "body", "entry-1", "2026-11-23T00:00:00Z"));
        var context = seen.get();
        assertThat(context.messageType()).isEqualTo(MarkdownTransformContext.MarkdownMessageType.SUMMARY);
        assertThat(context.messageId()).isEqualTo("entry-1");
        assertThat(context.timestamp()).isEqualTo("2026-11-23T00:00:00Z");

        MessageBubble.lines(new ChatMessage.SkillCard("n", "c", null, "entry-2", "t2"));
        assertThat(seen.get().messageType()).isEqualTo(MarkdownTransformContext.MarkdownMessageType.CUSTOM);
        assertThat(seen.get().messageId()).isEqualTo("entry-2");
    }

    // ── bash 卡（漏项#7）──────────────────────────────────────────────

    /** 头行＋空输出 `(no output)` 不渲臂：pi 空输出无输出段；cancelled 状态行。 */
    @Test
    void bashCardHeaderAndStatusLines() {
        var lines = MessageBubble.lines(new ChatMessage.BashCard(
            "git status", "On branch main", 0, false, false, null, false))
            .stream().map(LogicalLine::markup).toList();
        assertThat(lines.get(0)).isEqualTo("$ git status");
        assertThat(lines.get(1)).isEmpty();
        assertThat(lines.get(2)).isEqualTo("On branch main");
        assertThat(lines).hasSize(3); // 非零 exit/cancelled/truncated 都无 ⇒ 无状态段

        var cancelled = MessageBubble.lines(new ChatMessage.BashCard(
            "sleep 99", "partial", 9, true, false, null, false))
            .stream().map(LogicalLine::markup).toList();
        assertThat(cancelled.get(cancelled.size() - 1)).isEqualTo("(cancelled)");

        var failed = MessageBubble.lines(new ChatMessage.BashCard(
            "cmd", "", 2, false, false, null, false))
            .stream().map(LogicalLine::markup).toList();
        assertThat(failed.get(failed.size() - 1)).isEqualTo("(exit 2)");

        var truncated = MessageBubble.lines(new ChatMessage.BashCard(
            "cmd", "x", 0, false, true, "/tmp/full.log", false))
            .stream().map(LogicalLine::markup).toList();
        assertThat(truncated.get(truncated.size() - 1))
            .isEqualTo("Output truncated. Full output: /tmp/full.log");
    }

    /** 折叠＝尾部 20 行＋`... N more lines (ctrl+o to expand)`；展开＝全部＋collapse 提示。 */
    @Test
    void bashCardPreviewWindowAndExpandHint() {
        var output = String.join("\n", java.util.stream.IntStream.rangeClosed(1, 25)
            .mapToObj(i -> "line" + i).toList());
        var collapsed = MessageBubble.lines(new ChatMessage.BashCard(
            "cmd", output, 0, false, false, null, false))
            .stream().map(LogicalLine::markup).toList();
        assertThat(collapsed).noneMatch(m -> m.equals("line5"));
        assertThat(collapsed).contains("line6", "line25");
        assertThat(collapsed).contains("... 5 more lines (ctrl+o to expand)");

        MessageBubbleVisibility.installToolsExpanded(() -> true);
        var expanded = MessageBubble.lines(new ChatMessage.BashCard(
            "cmd", output, 0, false, false, null, false))
            .stream().map(LogicalLine::markup).toList();
        assertThat(expanded).contains("line1", "line25");
        assertThat(expanded).contains("(ctrl+o to collapse)");
    }

    /** `!!`（excludeFromContext）⇒ 头行 dim ≙ pi bashMode→dim。 */
    @Test
    void bashCardExcludedFromContextIsDim() {
        var lines = MessageBubble.lines(new ChatMessage.BashCard(
            "verbose", "", 0, false, false, null, true));
        assertThat(lines.get(0).markup()).isEqualTo("$ verbose");
        assertThat(lines.get(0).style()).isEqualTo(dev.tamboui.style.Style.EMPTY.dim());
    }
}
