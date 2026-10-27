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

    // ── 批 25（docs/36 T7/T8）：Markdown 接线 ─────────────────

    @Test
    void assistantTextRendersMarkdownBlocksEndToEnd() {
        var lines = MessageBubble.lines(new ChatMessage.Assistant(List.of(
            new ContentBlock.TextContent("**bold** answer\n\n```java\nint x = 1;\n```"))));

        // 首行 "• " 前缀＋行内 bold 标签（行 style EMPTY，样式全在行内）
        assertThat(lines.get(0).markup()).isEqualTo("• [bold]bold[/] answer");
        assertThat(lines.get(1).markup()).isEmpty();          // 块间空行（space token）
        // 代码围栏行可见＋preformatted；缩进随消息 +2 而内容自身带 codeBlockIndent
        assertThat(lines.get(2).markup()).isEqualTo("```java");
        assertThat(lines.get(2).preformatted()).isTrue();
        assertThat(lines.get(2).initialIndent()).isEqualTo(2);
        assertThat(lines.get(3).markup()).startsWith("  [#");
        assertThat(lines.get(3).markup()).contains("int[/]");
        assertThat(lines.get(4).markup()).isEqualTo("```");
    }

    @Test
    void assistantTableAndLinkRenderThrough() {
        var lines = MessageBubble.lines(new ChatMessage.Assistant(List.of(
            new ContentBlock.TextContent("| a |\n|---|\n| 1 |\n\nsee [pi](https://e.com)"))));
        var markups = lines.stream().map(LogicalLine::markup).toList();
        // 首块是表格 ⇒ "• " 前缀落表格顶行；链接段落行无前缀
        assertThat(markups.get(0)).isEqualTo("• ┌───┐");
        assertThat(markups).anyMatch(m -> m.startsWith("see ")
            && m.contains("[link=https://e.com]"));
    }

    @Test
    void thinkingBlockRendersMarkdownWithThinkingStyle() {
        var lines = MessageBubble.lines(new ChatMessage.Assistant(List.of(
            new ContentBlock.ThinkingContent("let me **think**", null, false))));

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).markup()).isEqualTo("let me [bold]think[/]");
        // pi :146-162：defaultTextStyle＝thinkingText 色＋italic
        assertThat(lines.get(0).style().fg()).isPresent();
        assertThat(lines.get(0).style().addModifiers())
            .contains(dev.tamboui.style.Modifier.ITALIC);
    }

    @Test
    void markdownVariantRendersThroughPipeline() {
        var lines = MessageBubble.lines(new ChatMessage.Markdown("**T**\n\n| a |\n|---|"));
        assertThat(lines.get(0).markup()).isEqualTo("[bold]T[/]");
        assertThat(lines.stream().map(LogicalLine::markup).toList())
            .anyMatch(m -> m.startsWith("┌"));
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
                MetaKind.COMPACTION, MetaKind.BRANCH, MetaKind.CUSTOM}) {
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
        assertThat(ChatMessage.from(new Entry.Compaction(
                "c", 0, null, null, "kept 3 msgs", null, List.of(), 100, null, null, null)))
            .isEqualTo(new ChatMessage.System(
                "Compacted context: kept 3 msgs", MetaKind.COMPACTION));
        assertThat(ChatMessage.from(new Entry.BranchSummary(
                "b", 0, null, null, "from-1", "summ", null, null, null)))
            .isEqualTo(new ChatMessage.System("Branch: summ", MetaKind.BRANCH));
        // 批 26（docs/37 T10）：无渲染器的 custom 条目不渲（pi
        // interactive-mode.ts:3848-3850——getEntryRenderer 缺席 ⇒ 直接 return）。
        assertThat(ChatMessage.from(new Entry.Custom(
                "x", 0, null, null, "progress", null)))
            .isNull();
    }

    @Test
    void customMessageDisplayGateAndLabel() {
        assertThat(ChatMessage.from(new Entry.CustomMessage(
                "cm", 0, null, null, "my-ext",
                com.pijava.agent.entry.CustomMessageContent.of("injected"), true, null)))
            .isEqualTo(new ChatMessage.System("[my-ext] injected", MetaKind.CUSTOM));
        assertThat(ChatMessage.from(new Entry.CustomMessage(
                "cm", 0, null, null, "my-ext",
                com.pijava.agent.entry.CustomMessageContent.of(List.<ContentBlock>of(
                    new ContentBlock.TextContent("a"),
                    new ContentBlock.ImageContent("image/png", "eA=="))),
                true, null)))
            .isEqualTo(new ChatMessage.System("[my-ext] a", MetaKind.CUSTOM));
        assertThat(ChatMessage.from(new Entry.CustomMessage(
                "cm", 0, null, null, "my-ext",
                com.pijava.agent.entry.CustomMessageContent.of("hidden"), false, null)))
            .isNull();
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

    // ── 批 12 T6（docs/23 §3/§7）：outputPad 烘焙包装 ─────────────

    @Test
    void paddedShiftsEveryLineIndent() {
        var plain = MessageBubble.lines(new ChatMessage.User("hi"));
        var padded = MessageBubble.lines(new ChatMessage.Padded(new ChatMessage.User("hi"), 1));

        assertThat(padded).hasSize(plain.size());
        for (int i = 0; i < plain.size(); i++) {
            assertThat(padded.get(i).initialIndent()).isEqualTo(plain.get(i).initialIndent() + 1);
            assertThat(padded.get(i).subsequentIndent())
                .isEqualTo(plain.get(i).subsequentIndent() + 1);
            assertThat(padded.get(i).markup()).isEqualTo(plain.get(i).markup());
        }
    }

    @Test
    void paddedWrapsToolCardsToo() {
        var card = new ChatMessage.Assistant(List.of(
            new ContentBlock.ToolUseContent("id", "bash", java.util.Map.of("command", "x"))));
        var lines = MessageBubble.lines(new ChatMessage.Padded(card, 1));

        // 工具卡两行（名称行 indent 0→1、参数行 indent 4→5）。
        assertThat(lines.get(0).initialIndent()).isEqualTo(1);
        assertThat(lines.get(1).initialIndent()).isEqualTo(5);
    }

    @Test
    void fromWithPadBakesPaddedOnlyAboveZero() {
        var entry = new Entry.ModelChange("m", 0, null, null, "deepseek", "v4");

        assertThat(ChatMessage.from(entry, 0))
            .isEqualTo(new ChatMessage.System("Model: deepseek/v4", MetaKind.MODEL_CHANGE));
        assertThat(ChatMessage.from(entry, 1))
            .isEqualTo(new ChatMessage.Padded(
                new ChatMessage.System("Model: deepseek/v4", MetaKind.MODEL_CHANGE), 1));
        assertThat(ChatMessage.padOf(ChatMessage.from(entry, 1))).isEqualTo(1);
        assertThat(ChatMessage.padOf(ChatMessage.from(entry, 0))).isZero();
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
