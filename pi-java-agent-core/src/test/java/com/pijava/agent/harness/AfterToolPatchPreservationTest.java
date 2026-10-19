package com.pijava.agent.harness;

import java.util.List;
import java.util.Map;

import com.pijava.agent.hook.AfterToolPatch;
import com.pijava.agent.hook.BeforeToolResult;
import com.pijava.agent.hook.HookSystem;
import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ExecutionMode;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolRegistry;
import com.pijava.agent.tool.ToolResult;
import com.pijava.agent.tool.ToolUpdateCallback;
import com.pijava.ai.AbortSignal;
import com.pijava.ai.message.ContentBlock;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/14 §2.3/§3.4.4 的存量修真：{@code applyAfterToolPatch} 旧实现用 5 参构造器
 * 重建 {@link ToolResult}，任何补丁（哪怕只改 details）都会把结果的 {@code isError}
 * 洗成 {@code false}、{@code structuredContent} 洗成 {@code null}。pi 的合并只发生在
 * 逐字段 {@code ??} 上（agent-loop.ts:744-752），未设的字段必须原样活着。
 */
class AfterToolPatchPreservationTest {

    private static final ToolContext CTX = new ToolContext(
        System.getProperty("java.io.tmpdir"), Map.of(),
        new DefaultShellExecutor(), new DefaultFileSystem());

    private static final PiLoop.Sink NO_EMIT = event -> {};

    /** 结果带 isError=true + structuredContent 的工具（MCP 形状：服务器答了，但答的是错）。 */
    private static AgentTool<String, Void> structuredErrorTool(String name, Object structured) {
        return new AgentTool<>() {
            @Override public String name() { return name; }
            @Override public String label() { return name; }
            @Override public String description() { return "structured error tool"; }
            @Override public Map<String, Object> inputSchema() { return Map.of(); }
            @Override public ExecutionMode executionMode() { return new ExecutionMode.Sequential(); }
            @Override public String prepareArguments(Map<String, Object> raw) { return "prepared"; }
            @Override public ToolResult<Void> execute(String id, String params, AbortSignal signal,
                    ToolUpdateCallback<Void> onUpdate, ToolContext ctx) {
                return new ToolResult<>(List.of(new ContentBlock.TextContent("payload")),
                    null, null, false, List.of(), true, structured);
            }
        };
    }

    private static PiLoop.ToolOutcome runBoth(PiToolRunner runner, PiLoop.ToolCall call) {
        var preparation = runner.prepare(call);
        return preparation instanceof PiLoop.ImmediateOutcome immediate
            ? immediate.outcome()
            : runner.execute((PiLoop.Prepared) preparation, NO_EMIT);
    }

    /** 修真夹具：只改 details 的补丁不许洗掉 isError/structuredContent。 */
    @Test
    void detailsOnlyPatchPreservesIsErrorAndStructuredContent() {
        var structured = Map.of("rows", 3);
        var hooks = new HookSystem(new LaneState());
        hooks.onAfterTool("default", ctx -> new AfterToolPatch(
            null, Map.of("patched", true), null, null, null));
        var runner = new PiToolRunner("default",
            registryWith(structuredErrorTool("mcp__s__t", structured)), hooks, CTX, null, null);

        var outcome = runBoth(runner, new PiLoop.ToolCall("tc1", "mcp__s__t", Map.of(), false));

        assertThat(outcome.result().isError()).as("补丁没碰 isError ⇒ 保留").isTrue();
        assertThat(outcome.result().structuredContent()).as("补丁没碰 structuredContent ⇒ 保留")
            .isEqualTo(structured);
        assertThat(outcome.result().details()).isEqualTo(Map.of("patched", true));
    }

    /** 补丁显式替换 structuredContent ⇒ 新值生效（pi emitToolResult 的独立合并臂）。 */
    @Test
    void patchCanReplaceStructuredContent() {
        var hooks = new HookSystem(new LaneState());
        hooks.onAfterTool("default", ctx -> new AfterToolPatch(
            null, null, null, null, null, Map.of("replaced", true), false));
        var runner = new PiToolRunner("default",
            registryWith(structuredErrorTool("t", Map.of("old", 1))), hooks, CTX, null, null);

        var outcome = runBoth(runner, new PiLoop.ToolCall("tc1", "t", Map.of(), false));

        assertThat(outcome.result().structuredContent()).isEqualTo(Map.of("replaced", true));
    }

    /** 补丁置 dropStructuredContent ⇒ 清空（pi：content 被替换而 structured 未同换 ⇒ delete）。 */
    @Test
    void patchCanDropStructuredContent() {
        var hooks = new HookSystem(new LaneState());
        hooks.onAfterTool("default", ctx -> new AfterToolPatch(
            List.of(new ContentBlock.TextContent("rewritten")), null, null, null, null,
            null, true));
        var runner = new PiToolRunner("default",
            registryWith(structuredErrorTool("t", Map.of("old", 1))), hooks, CTX, null, null);

        var outcome = runBoth(runner, new PiLoop.ToolCall("tc1", "t", Map.of(), false));

        assertThat(outcome.result().structuredContent()).isNull();
        assertThat(outcome.result().content().toString()).contains("rewritten");
    }

    /** after_tool 钩子看得到 before_tool 改写后的参数（pi ToolResultEventBase.input，types.ts:1247）。 */
    @Test
    void afterToolHookSeesTheRewrittenArguments() {
        var seen = new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>();
        var hooks = new HookSystem(new LaneState());
        hooks.onBeforeTool("default", ctx -> BeforeToolResult.modify(Map.of("rewritten", 1)));
        hooks.onAfterTool("default", ctx -> {
            seen.set(ctx.arguments());
            return null;
        });
        var runner = new PiToolRunner("default",
            registryWith(structuredErrorTool("t", null)), hooks, CTX, null, null);

        runBoth(runner, new PiLoop.ToolCall("tc1", "t", Map.of("original", 0), false));

        assertThat(seen.get()).as("pi 的 prepared.args：钩子看到改写后的参数")
            .isEqualTo(Map.of("rewritten", 1));
    }

    private static ToolRegistry registryWith(AgentTool<?, ?> tool) {
        var registry = new ToolRegistry(null);
        registry.register(tool);
        return registry;
    }
}
