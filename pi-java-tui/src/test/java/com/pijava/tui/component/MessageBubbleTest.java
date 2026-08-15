package com.pijava.tui.component;

import java.util.List;

import com.pijava.ai.message.ContentBlock;

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
        assertThat(MessageBubble.lines(new ChatMessage.ToolResult("output", false)))
            .isNotEmpty();
        assertThat(MessageBubble.lines(new ChatMessage.Error("boom"))).isNotEmpty();
        assertThat(MessageBubble.lines(new ChatMessage.System("info"))).isNotEmpty();
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
            "x".repeat(600), false));
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
        var err = MessageBubble.lines(new ChatMessage.ToolResult("bad", true));
        assertThat(err.get(0).markup()).isEqualTo("! bad");
        assertThat(err.get(0).style()).isEqualTo(Style.EMPTY.red());
    }

    @Test
    void systemLinesAreDim() {
        var lines = MessageBubble.lines(new ChatMessage.System("info"));
        assertThat(lines).isNotEmpty();
        assertThat(lines.get(0).style()).isEqualTo(Style.EMPTY.dim());
    }

    @Test
    void chatMessageProjectsToolEntry() {
        var entry = new com.pijava.agent.entry.Entry.Message(
            com.pijava.agent.entry.Entry.newHeader(0, ""), "tool",
            List.of(new ContentBlock.ToolResultContent(
                "id", "read", List.of(new ContentBlock.TextContent("file")), false)));

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
