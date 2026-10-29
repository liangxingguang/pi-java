package com.pijava.agent.harness;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.pijava.agent.prompt.SystemPromptOptions.ContextFile;
import com.pijava.agent.prompt.SystemPrompts;
import com.pijava.agent.skill.SkillManager;
import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ExecutionMode;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolRegistry;
import com.pijava.agent.tool.ToolResult;
import com.pijava.agent.tool.ToolUpdateCallback;
import com.pijava.ai.AbortSignal;
import com.pijava.telemetry.NoopTelemetryContext;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 30（T3，B94 裁决①）：{@link ContextAssembler} 的 tools 段对齐 pi 稀疏语义——
 * 只收**声明了 snippet** 的工具（description 回落删除）；片段/准则过 pi 的归一
 * （agent-session.ts:1676-1697：折行压缩/trim/去重保序）。
 */
class ContextAssemblerToolSnippetsTest {

    /** 假工具：snippet/guidelines 可注入。 */
    private static AgentTool<Map<String, Object>, Object> fake(
            String name, String snippet, List<String> guidelines) {
        return new AgentTool<>() {
            @Override public String name() { return name; }
            @Override public String label() { return name; }
            @Override public String description() { return "description of " + name; }
            @Override public String promptSnippet() { return snippet; }
            @Override public List<String> promptGuidelines() {
                return guidelines == null ? List.of() : guidelines;
            }
            @Override public Map<String, Object> inputSchema() { return Map.of("type", "object"); }
            @Override public ExecutionMode executionMode() { return new ExecutionMode.Parallel(); }
            @Override public ToolResult<Object> execute(String toolCallId,
                    Map<String, Object> params, AbortSignal signal,
                    ToolUpdateCallback<Object> onUpdate, ToolContext context) {
                return ToolResult.success("ok");
            }
        };
    }

    /** 最小上下文：注册表＋活跃集＝给定工具。 */
    private static ContextAssembler assemblerOf(AgentTool<?, ?>... tools) {
        var registry = new ToolRegistry(null);
        var lane = new LaneState();
        for (var tool : tools) {
            registry.register(tool);
        }
        var active = java.util.Set.<AgentTool<?, ?>>of(tools);
        var ctx = new ExecutionContext(
            null, () -> null, () -> com.pijava.ai.thinking.ModelThinkingLevel.off(),
            () -> "", () -> active,
            0, null, null,
            registry, null, new SkillManager(),
            null, lane, null, null,
            null, null, null, null,
            null, null, null, NoopTelemetryContext.INSTANCE, null,
            null, null, null, java.util.List::of, () -> "",
            (Supplier<List<ContextFile>>) List::of, null,
            java.util.Map::of);
        return new ContextAssembler(ctx);
    }

    /** 未声明 snippet 的工具不进 tools 段（pi system-prompt.ts:151 过滤；裁决①删回落）。 */
    @Test
    void toolWithoutSnippetStaysOutOfToolsSection() {
        var assembler = assemblerOf(fake("no_snippet", "", List.of()));
        var options = assembler.promptOptions(new LaneState());
        assertThat(options).isNotNull();
        assertThat(options.toolSnippets()).isEmpty();
        // 全表空 ⇒ pi 的 (none) 臂（system-prompt.ts:157）。
        assertThat(SystemPrompts.build(options)).contains("(none)");
    }

    /** 多行 snippet 折成单行（pi _normalizePromptSnippet 逐字）。 */
    @Test
    void multiLineSnippetIsFoldedToOneLine() {
        var assembler = assemblerOf(fake("fold", "line one\n  line   two\r\nthree", List.of()));
        var options = assembler.promptOptions(new LaneState());
        assertThat(options).isNotNull();
        assertThat(options.toolSnippets())
            .containsEntry("fold", "line one line two three");
    }

    /** 全空白 snippet 视作未声明（pi 归一 ⇒ undefined ⇒ 不进表）。 */
    @Test
    void blankSnippetIsAbsent() {
        var assembler = assemblerOf(fake("blank", "  \n ", List.of()));
        var options = assembler.promptOptions(new LaneState());
        assertThat(options).isNotNull();
        assertThat(options.toolSnippets()).isEmpty();
    }

    /** guidelines 逐条 trim、丢空、按字面值去重保序（pi _normalizePromptGuidelines 逐字）。 */
    @Test
    void guidelinesAreTrimmedDedupedAndOrdered() {
        var assembler = assemblerOf(fake("g", "does g",
            List.of("  keep me  ", "", "keep me", "   ", "second")));
        var options = assembler.promptOptions(new LaneState());
        assertThat(options).isNotNull();
        assertThat(options.toolGuidelines())
            .containsEntry("g", List.of("keep me", "second"));
    }
}
