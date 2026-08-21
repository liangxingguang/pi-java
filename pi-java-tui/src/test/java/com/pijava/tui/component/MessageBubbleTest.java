package com.pijava.tui.component;

import java.util.List;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

import dev.tamboui.style.Style;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 alignment design §5.3: every ChatMessage variant projects to
 * logical lines without exception.
 */
class MessageBubbleTest {

    @Test
    void rendersAllVariants() {
        assertThat(MessageBubble.lines(new ChatMessage.User("hello"))).isNotEmpty();
        assertThat(MessageBubble.lines(new ChatMessage.Assistant(List.of(
            new ContentBlock.TextContent("reply"))))).isNotEmpty();
        assertThat(MessageBubble.lines(new ChatMessage.ToolCall("read", "{}")))
            .hasSize(2);
        assertThat(MessageBubble.lines(new ChatMessage.ToolResult(
                List.of(new ContentBlock.TextContent("output")), false)))
            .isNotEmpty();
        assertThat(MessageBubble.lines(new ChatMessage.Error("boom"))).isNotEmpty();
        assertThat(MessageBubble.lines(new ChatMessage.System("info", MetaKind.GENERIC))).isNotEmpty();
        assertThat(MessageBubble.lines(new ChatMessage.TurnSeparator("done"))).isNotEmpty();
    }

    @Test
    void toolCallCardProducesNameRowAndPreformattedArgsRow() {
        var lines = new ToolCallCard("read", "{\"path\": \"a.txt\"}", "running").lines();
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).markup()).contains("[cyan]read[/]");
        assertThat(lines.get(1).preformatted()).isTrue();
        assertThat(lines.get(1).markup()).contains("a.txt");
    }

    @Test
    void errorUsesMarkupForRedText() {
        var lines = MessageBubble.lines(new ChatMessage.Error("boom"));
        assertThat(lines.get(0).markup()).isEqualTo("[red]boom[/]");
    }

    @Test
    void messageTextIsEscapedSoMarkupRendersLiterally() {
        var lines = MessageBubble.lines(new ChatMessage.Assistant(List.of(
            new ContentBlock.TextContent("use arr[0], [red], \\"))));
        assertThat(lines.get(0).markup())
            .isEqualTo("• use arr[[0]], [[red]], \\\\");
    }

    @Test
    void toolResultIsPreformattedTruncatedIndentedAndDim() {
        var lines = MessageBubble.lines(new ChatMessage.ToolResult(
            List.of(new ContentBlock.TextContent("x".repeat(600))), false));
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).preformatted()).isTrue();
        assertThat(lines.get(0).initialIndent()).isEqualTo(4);
        assertThat(lines.get(0).style()).isEqualTo(Style.EMPTY.dim());
        assertThat(lines.get(0).markup()).hasSize(501); // 500 + ellipsis
    }

    @Test
    void userMessageGetsArrowPrefixAndContinuationIndent() {
        var lines = MessageBubble.lines(new ChatMessage.User("hi"));
        assertThat(lines.get(0).markup()).isEqualTo("› hi");
        assertThat(lines.get(0).initialIndent()).isZero();
        assertThat(lines.get(0).subsequentIndent()).isEqualTo(2);

        var multi = MessageBubble.lines(new ChatMessage.User("a\nb"));
        assertThat(multi.get(1).markup()).isEqualTo("b");
        assertThat(multi.get(1).initialIndent()).isEqualTo(2);
    }

    @Test
    void assistantMessageGetsBulletPrefix() {
        var lines = MessageBubble.lines(new ChatMessage.Assistant(List.of(
            new ContentBlock.TextContent("reply"))));
        assertThat(lines.get(0).markup()).isEqualTo("• reply");
    }

    @Test
    void turnSeparatorRendersDimRuleWithLabel() {
        var lines = MessageBubble.lines(new ChatMessage.TurnSeparator("Worked for 1m 2s"));
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).markup()).startsWith("─");
        assertThat(lines.get(0).markup()).contains("Worked for 1m 2s");
        assertThat(lines.get(0).style()).isEqualTo(Style.EMPTY.dim());
    }

    @Test
    void toolResultErrorIsMarkedAndRed() {
        var err = MessageBubble.lines(new ChatMessage.ToolResult(
            List.of(new ContentBlock.TextContent("bad")), true));
        assertThat(err.get(0).markup()).isEqualTo("! bad");
        assertThat(err.get(0).style()).isEqualTo(Style.EMPTY.red());
    }

    @Test
    void toolResultRendersDiffBlocksViaDiffView() {
        var lines = MessageBubble.lines(new ChatMessage.ToolResult(
            List.of(
                new ContentBlock.TextContent("done"),
                new ContentBlock.DiffContent("+new\n-old")),
            false));
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0).markup()).isEqualTo("done");
        assertThat(lines.get(0).initialIndent()).isEqualTo(4);
        assertThat(lines.get(0).style()).isEqualTo(Style.EMPTY.dim());
        assertThat(lines.get(1).markup()).isEqualTo("+new");
        assertThat(lines.get(1).initialIndent()).isEqualTo(4);
        assertThat(lines.get(1).style()).isEqualTo(Style.EMPTY.fg(DiffView.ADDED));
        assertThat(lines.get(2).markup()).isEqualTo("-old");
        assertThat(lines.get(2).style()).isEqualTo(Style.EMPTY.fg(DiffView.REMOVED));
    }

    @Test
    void systemLinesAreDim() {
        var lines = MessageBubble.lines(new ChatMessage.System("info", MetaKind.GENERIC));
        assertThat(lines).isNotEmpty();
        assertThat(lines.get(0).markup()).isEqualTo("info");
        assertThat(lines.get(0).style()).isEqualTo(Style.EMPTY.dim());
    }

    @Test
    void metadataKindsRenderIconAndColor() {
        for (var kind : new MetaKind[] {MetaKind.MODEL_CHANGE, MetaKind.THINKING_LEVEL,
                MetaKind.ACTIVE_TOOLS, MetaKind.COMPACTION, MetaKind.BRANCH, MetaKind.CUSTOM}) {
            var lines = MessageBubble.lines(new ChatMessage.System("hello", kind));
            assertThat(lines).hasSize(1);
            assertThat(lines.get(0).markup()).isEqualTo(kind.icon() + " hello");
            assertThat(lines.get(0).style()).isEqualTo(Style.EMPTY.fg(kind.color()));
        }
    }

    @Test
    void metadataEntriesMapToDistinctKinds() {
        assertThat(ChatMessage.from(new Entry.ModelChange(
                "m", 0, null, null, "deepseek", "v4")))
            .isEqualTo(new ChatMessage.System("Model: deepseek/v4", MetaKind.MODEL_CHANGE));
        assertThat(ChatMessage.from(new Entry.ThinkingLevelChange(
                "t", 0, null, null, "high")))
            .isEqualTo(new ChatMessage.System("Thinking: high", MetaKind.THINKING_LEVEL));
        assertThat(ChatMessage.from(new Entry.ActiveToolsChange(
                "a", 0, null, null, List.of("bash", "write"))))
            .isEqualTo(new ChatMessage.System("Tools: bash, write", MetaKind.ACTIVE_TOOLS));
        assertThat(ChatMessage.from(new Entry.Compaction(
                "c", 0, null, null, "kept 3 msgs", List.of(), 100, null, null)))
            .isEqualTo(new ChatMessage.System(
                "Compacted context: kept 3 msgs", MetaKind.COMPACTION));
        assertThat(ChatMessage.from(new Entry.BranchSummary(
                "b", 0, null, null, "from-1", "summ", null, null)))
            .isEqualTo(new ChatMessage.System("Branch: summ", MetaKind.BRANCH));
        assertThat(ChatMessage.from(new Entry.Custom(
                "x", 0, null, null, "progress", null)))
            .isEqualTo(new ChatMessage.System("Custom event: progress", MetaKind.CUSTOM));
    }

    @Test
    void chatMessageProjectsToolEntry() {
        var entry = new com.pijava.agent.entry.Entry.Message(
            "t-1", 0, null, null,
            new Message.ToolResultMessage("id", "read",
                List.of(new ContentBlock.TextContent("file")), false), null);

        var message = ChatMessage.from(entry);
        assertThat(message).isInstanceOf(ChatMessage.ToolResult.class);
    }
    @Test
    void toolCardInAssistantBlocksGetsNoBulletPrefix() {
        var lines = MessageBubble.lines(new ChatMessage.Assistant(List.of(
            new ContentBlock.ToolUseContent("id", "bash",
                java.util.Map.of("command", "echo hi")))));
        assertThat(lines.get(0).markup()).startsWith("[cyan]bash[/]");
    }

    @Test
    void toolCardUnwrapsRawArgumentsAndFallsBackName() {
        var lines = MessageBubble.lines(new ChatMessage.Assistant(List.of(
            new ContentBlock.ToolUseContent("id", "",
                java.util.Map.of("_raw", "{\"command\": \"curl ...\"}")))));
        assertThat(lines.get(0).markup()).contains("[cyan]tool[/]");
        assertThat(lines.get(1).markup()).contains("curl ...");
    }
}
