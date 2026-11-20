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
        MessageBubbleVisibility.installToolsExpanded(null);
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
        var fallback = markups(toolUse("custom_tool", Map.of("path", "/tmp/x")));
        assertThat(fallback).isNotEmpty();

        var registry = new ToolRendererRegistry();
        registry.register(McpToolCallRenderer.resolver());
        ToolRenderers.install(registry);
        // 非 mcp__ 名且非内置工具：内置链尾也不管 ⇒ ToolCallCard（与无注册表同输出）。
        assertThat(markups(toolUse("custom_tool", Map.of("path", "/tmp/x"))))
            .isEqualTo(fallback);
    }

    /** docs/60：内置渲染器坐链尾——空注册表也能接管 8 个内置工具。 */
    @Test
    void builtInRenderersFillTheChainTail() {
        ToolRenderers.install(new ToolRendererRegistry());

        assertThat(markups(toolUse("bash", Map.of("command", "echo hi"))))
            .containsExactly("$ echo hi");
        assertThat(markups(toolUse("read", Map.of("path", "a.txt", "limit", 2))))
            .containsExactly("read a.txt:1-2");
        assertThat(markups(toolUse("custom_tool", Map.of()))).isNotEmpty();
    }

    /** 扩展优先：先注册的 resolver 抢在内置之前。 */
    @Test
    void extensionResolversWinOverBuiltIns() {
        var registry = new ToolRendererRegistry();
        registry.register((name, next) -> "bash".equals(name)
            ? new StubRenderer(List.of("ext bash"))
            : next.get());
        ToolRenderers.install(registry);

        assertThat(markups(toolUse("bash", Map.of("command", "echo hi"))))
            .containsExactly("ext bash");
    }

    /** docs/60 §2.4：结果气泡按工具名查渲染器；edit 保持"无意见" ⇒ DiffView 原样。 */
    @Test
    void toolResultBubbleConsultsRenderers() {
        ToolRenderers.install(new ToolRendererRegistry());

        var read = MessageBubble.lines(new ChatMessage.ToolResult(
            List.of(new ContentBlock.TextContent("l1\nl2")), false, null, "read"));
        assertThat(read).as("read 折叠态不显示结果").isEmpty();

        var edit = MessageBubble.lines(new ChatMessage.ToolResult(
            List.of(new ContentBlock.DiffContent("+new\n-old")), false, null, "edit"));
        assertThat(edit).extracting(LogicalLine::markup).contains("+new", "-old");
    }

    private record StubRenderer(List<String> lines) implements com.pijava.coding.agent.extension.ToolCallRenderer {
        @Override
        public List<String> renderCall(String toolName, Map<String, Object> args, boolean expanded) {
            return lines;
        }
    }

    /** 批 34（K8）：投影现读全局展开态——true ⇒ 展开臂全量，无折叠提示行。 */
    @Test
    void expandedReadingDrivesRendererExpandArm() {
        var registry = new ToolRendererRegistry();
        registry.register(McpToolCallRenderer.resolver());
        ToolRenderers.install(registry);
        var seven = "l1\nl2\nl3\nl4\nl5\nl6\nl7";

        MessageBubbleVisibility.installToolsExpanded(() -> true);
        var expanded = markups(toolResult("mcp__s__t", seven));
        assertThat(expanded).doesNotContain("... (2 more lines)")
            .contains("l7");

        MessageBubbleVisibility.installToolsExpanded(() -> false);
        assertThat(markups(toolResult("mcp__s__t", seven)))
            .contains("... (2 more lines)");

        MessageBubbleVisibility.installToolsExpanded(null);   // 回落 false
        assertThat(markups(toolResult("mcp__s__t", seven)))
            .contains("... (2 more lines)");
    }
}
