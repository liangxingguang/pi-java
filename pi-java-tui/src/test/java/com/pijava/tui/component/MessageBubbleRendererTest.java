package com.pijava.tui.component;

import java.util.List;
import java.util.Map;

import com.pijava.ai.message.ContentBlock;
import com.pijava.coding.agent.extension.McpToolCallRenderer;
import com.pijava.coding.agent.extension.ToolRendererRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 16（T4d，docs/27 §7）：MessageBubble 的 ToolUse/ToolResult 两臂在
 * ToolCallCard 之前查渲染器责任链——命中用其行，未命中原路径不变。
 */
class MessageBubbleRendererTest {

    @AfterEach
    void tearDown() {
        ToolRenderers.install(null);
    }

    private static ChatMessage.Assistant toolUse(String name, Map<String, Object> args) {
        return new ChatMessage.Assistant(List.of(new ContentBlock.ToolUseContent(
            "call-1", name, args)));
    }

    private static ChatMessage.Assistant toolResult(String name, String text) {
        return new ChatMessage.Assistant(List.of(new ContentBlock.ToolResultContent(
            "call-1", name,
            List.of(new ContentBlock.TextContent(text)), false)));
    }

    private static List<String> markups(ChatMessage msg) {
        return MessageBubble.lines(msg).stream().map(LogicalLine::markup).toList();
    }

    /** 命中：mcp__ 名走 MCP 渲染器的 label 行（server/tool）。 */
    @Test
    void mcpToolCallUsesRendererLabel() {
        var registry = new ToolRendererRegistry();
        registry.register(McpToolCallRenderer.resolver());
        ToolRenderers.install(registry);

        assertThat(markups(toolUse("mcp__github__create_issue", Map.of("title", "bug"))))
            .anySatisfy(line -> assertThat(line).startsWith("github/create_issue title="));
    }

    /** 命中：结果臂折叠提示行进气泡（首 5 行＋"... (N more lines)"）。 */
    @Test
    void mcpToolResultUsesRendererCollapseHint() {
        var registry = new ToolRendererRegistry();
        registry.register(McpToolCallRenderer.resolver());
        ToolRenderers.install(registry);

        var seven = "l1\nl2\nl3\nl4\nl5\nl6\nl7";
        assertThat(markups(toolResult("mcp__s__t", seven)))
            .contains("... (2 more lines)");
    }

    /** 未命中（无注册表 / 非 mcp__ 名）⇒ 原 ToolCallCard 路径不变。 */
    @Test
    void unregisteredToolFallsBackToToolCallCard() {
        ToolRenderers.install(null);
        var fallback = markups(toolUse("read", Map.of("path", "/tmp/x")));
        assertThat(fallback).isNotEmpty();

        var registry = new ToolRendererRegistry();
        registry.register(McpToolCallRenderer.resolver());
        ToolRenderers.install(registry);
        // 非 mcp__ 名：resolver 不管 ⇒ ToolCallCard（与无注册表同输出）。
        assertThat(markups(toolUse("read", Map.of("path", "/tmp/x"))))
            .isEqualTo(fallback);
    }
}
