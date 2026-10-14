package com.pijava.agent.harness;

import java.util.List;
import java.util.Map;

import com.pijava.ai.AbortSignal;
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
import com.pijava.ai.message.ContentBlock;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * B234（pi #10549，agent-loop.test.ts:403-455）：{@code durationMs} 只围
 * {@code tool.execute()} 本体 —— 不含 before/after 钩子与校验；成功/抛错都有；
 * 未执行（unknown/blocked）无该字段；after 钩子抛错保留执行时长。
 */
class ToolDurationTest {

    private static final ToolContext CTX = new ToolContext(
        System.getProperty("java.io.tmpdir"), Map.of(),
        new DefaultShellExecutor(), new DefaultFileSystem());
    private static final PiLoop.Sink NO_EMIT = event -> {};

    private static AgentTool<String, Void> sleepingTool(String name, long sleepMs) {
        return new AgentTool<>() {
            @Override public String name() { return name; }
            @Override public String label() { return name; }
            @Override public String description() { return "sleeping tool"; }
            @Override public Map<String, Object> inputSchema() { return Map.of(); }
            @Override public ExecutionMode executionMode() { return new ExecutionMode.Sequential(); }
            @Override public String prepareArguments(Map<String, Object> raw) { return "ok"; }
            @Override public ToolResult<Void> execute(String id, String params, AbortSignal signal,
                    ToolUpdateCallback<Void> onUpdate, ToolContext ctx) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return ToolResult.success("done");
            }
        };
    }

    private static AgentTool<String, Void> throwingTool() {
        return new AgentTool<>() {
            @Override public String name() { return "boom"; }
            @Override public String label() { return "boom"; }
            @Override public String description() { return "always throws"; }
            @Override public Map<String, Object> inputSchema() { return Map.of(); }
            @Override public ExecutionMode executionMode() { return new ExecutionMode.Sequential(); }
            @Override public String prepareArguments(Map<String, Object> raw) { return "ok"; }
            @Override public ToolResult<Void> execute(String id, String params, AbortSignal signal,
                    ToolUpdateCallback<Void> onUpdate, ToolContext ctx) {
                throw new IllegalStateException("exploded");
            }
        };
    }

    private static PiLoop.ToolOutcome runBoth(PiToolRunner runner, String name) {
        var toolCall = new PiLoop.ToolCall("tc1", name, Map.of(), false);
        var preparation = runner.prepare(toolCall);
        assertThat(preparation).isInstanceOf(PiLoop.Prepared.class);
        return runner.execute((PiLoop.Prepared) preparation, NO_EMIT);
    }

    /** 执行成功：时长只围 execute() 本体；100ms 的 before 钩子不计入（pi 夹具边界 25..100）。 */
    @Test
    void durationMeasuresExecuteOnlyExcludingHooks() {
        var hooks = new HookSystem(new LaneState());
        hooks.onBeforeTool("default", ctx -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new BeforeToolResult(true, null);
        });
        var runner = new PiToolRunner("default",
            toolRegistry(sleepingTool("slow", 30)), hooks, CTX, null, null);

        long start = System.nanoTime();
        var outcome = runBoth(runner, "slow");
        long wallMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(outcome.isError()).isFalse();
        assertThat(outcome.message().durationMs())
            .as("execute sleep 30ms ⇒ 至少 25ms")
            .isGreaterThanOrEqualTo(25L);
        assertThat(outcome.message().durationMs())
            .as("before 钩子 sleep 100ms 不计入 ⇒ 小于 100ms")
            .isLessThan(100L);
        assertThat(wallMs).as("壁钟含钩子，守卫夹具有效性").isGreaterThanOrEqualTo(100L);
    }

    /** 工具抛错：错误结果同样带 execute() 本体时长。 */
    @Test
    void throwingToolStillCarriesDuration() {
        var runner = new PiToolRunner("default",
            toolRegistry(throwingTool()), null, CTX, null, null);

        var outcome = runBoth(runner, "boom");

        assertThat(outcome.isError()).isTrue();
        assertThat(outcome.message().durationMs()).isNotNull().isGreaterThanOrEqualTo(0L);
    }

    /** 未找到工具：准备相当场失败，无时长。 */
    @Test
    void unknownToolHasNoDuration() {
        var runner = new PiToolRunner("default",
            new ToolRegistry(null), null, CTX, null, null);

        var toolCall = new PiLoop.ToolCall("tc1", "missing", Map.of(), false);
        var preparation = runner.prepare(toolCall);
        assertThat(preparation).isInstanceOf(PiLoop.ImmediateOutcome.class);
        var outcome = ((PiLoop.ImmediateOutcome) preparation).outcome();

        assertThat(outcome.isError()).isTrue();
        assertThat(outcome.message().durationMs()).isNull();
    }

    /** before 钩子拒绝：immediate 失败，无时长（pi blocked 调用无 durationMs）。 */
    @Test
    void blockedByBeforeHookHasNoDuration() {
        var hooks = new HookSystem(new LaneState());
        hooks.onBeforeTool("default",
            ctx -> new BeforeToolResult(false, Map.of("reason", "no")));
        var runner = new PiToolRunner("default",
            toolRegistry(sleepingTool("echo", 30)), hooks, CTX, null, null);

        var toolCall = new PiLoop.ToolCall("blocked", "echo", Map.of(), false);
        var preparation = runner.prepare(toolCall);
        assertThat(preparation).isInstanceOf(PiLoop.ImmediateOutcome.class);
        var outcome = ((PiLoop.ImmediateOutcome) preparation).outcome();

        assertThat(outcome.isError()).isTrue();
        assertThat(outcome.message().durationMs()).isNull();
    }

    /** after 钩子抛错：错误结果保留 execute() 本体时长（pi finalize catch）。 */
    @Test
    void throwingAfterHookKeepsExecutionDuration() {
        var hooks = new HookSystem(new LaneState());
        hooks.onAfterTool("default", ctx -> { throw new RuntimeException("after boom"); });
        var runner = new PiToolRunner("default",
            toolRegistry(sleepingTool("echo", 30)), hooks, CTX, null, null);

        var outcome = runBoth(runner, "echo");

        assertThat(outcome.isError()).isTrue();
        assertThat(outcome.message().content())
            .extracting(c -> ((ContentBlock.TextContent) c).text())
            .as("收尾段抛错换成钩子异常文本")
            .anyMatch(t -> t.contains("after boom"));
        assertThat(outcome.message().durationMs())
            .as("execute() 本体时长保留")
            .isGreaterThanOrEqualTo(25L);
    }

    private static ToolRegistry toolRegistry(AgentTool<?, ?> tool) {
        var registry = new ToolRegistry(null);
        registry.register(tool);
        return registry;
    }
}
