package com.pijava.agent.harness;

import java.util.Map;

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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PiToolRunner} 的四条错误路径与成功路径（{@code docs/28 §5} 第 2 步的前置件）。
 *
 * <p>对应 pi {@code prepareToolCall} 的分支：denied（钩子拒绝）、unavailable（未找到）、
 * 参数非法（{@code ToolArgumentsValidator}）、执行异常，以及正常成功。四条错误路径都
 * 必须返回**错误结果消息**而不是抛出 —— 因为 pi 对它们同样发
 * {@code tool_execution_start}/{@code tool_execution_end}。</p>
 */
class PiToolRunnerTest {

    private static final ToolContext CTX = new ToolContext(
        System.getProperty("java.io.tmpdir"), Map.of(),
        new DefaultShellExecutor(), new DefaultFileSystem());

    /** 一个总是成功的工具；{@code label} 用返回文本以便断言。 */
    private static AgentTool<String, Void> okTool(String name, String text) {
        return new AgentTool<>() {
            @Override public String name() { return name; }
            @Override public String label() { return name; }
            @Override public String description() { return "test tool"; }
            @Override public Map<String, Object> inputSchema() { return Map.of(); }
            @Override public ExecutionMode executionMode() { return new ExecutionMode.Sequential(); }
            @Override public String prepareArguments(Map<String, Object> raw) { return "prepared"; }
            @Override public ToolResult<Void> execute(String id, String params, AbortSignal signal,
                    ToolUpdateCallback<Void> onUpdate, ToolContext ctx) {
                return ToolResult.success(text);
            }
        };
    }

    /** 一个总是抛异常的工具。 */
    private static AgentTool<String, Void> throwingTool(String name) {
        return new AgentTool<>() {
            @Override public String name() { return name; }
            @Override public String label() { return name; }
            @Override public String description() { return "boom"; }
            @Override public Map<String, Object> inputSchema() { return Map.of(); }
            @Override public ExecutionMode executionMode() { return new ExecutionMode.Sequential(); }
            @Override public String prepareArguments(Map<String, Object> raw) { return "prepared"; }
            @Override public ToolResult<Void> execute(String id, String params, AbortSignal signal,
                    ToolUpdateCallback<Void> onUpdate, ToolContext ctx) {
                throw new IllegalStateException("tool exploded");
            }
        };
    }

    private static PiLoop.ToolCall call(String name) {
        return new PiLoop.ToolCall("tc1", name, Map.of(), false);
    }

    private static ToolRegistry registryWith(AgentTool<?, ?> tool) {
        var registry = new ToolRegistry(null);
        registry.register(tool);
        return registry;
    }

    @Test
    void successfulCallProducesNonErrorResult() {
        var decisions = new java.util.ArrayList<String>();
        var runner = new PiToolRunner("default", registryWith(okTool("echo", "hello")),
            null, CTX, null,
            (callId, allowed) -> decisions.add(callId + "=" + allowed));

        var outcome = runner.run(call("echo"));

        assertThat(outcome.isError()).isFalse();
        assertThat(outcome.terminate()).isFalse();
        assertThat(outcome.message().toolUseId()).isEqualTo("tc1");
        assertThat(outcome.message().toolName()).isEqualTo("echo");
        assertThat(outcome.message().content().toString()).contains("hello");
        assertThat(decisions).containsExactly("tc1=true");
    }

    @Test
    void unknownToolBecomesErrorResultNotAnException() {
        var runner = new PiToolRunner("default", registryWith(okTool("echo", "hi")),
            null, CTX, null, null);

        var outcome = runner.run(call("nope"));

        assertThat(outcome.isError()).isTrue();
        assertThat(outcome.terminate()).isFalse();
        assertThat(outcome.message().content().toString()).contains("nope");
    }

    @Test
    void throwingToolBecomesErrorResultNotAnException() {
        var runner = new PiToolRunner("default", registryWith(throwingTool("boom")),
            null, CTX, null, null);

        var outcome = runner.run(call("boom"));

        assertThat(outcome.isError()).isTrue();
        assertThat(outcome.message().content().toString()).contains("tool exploded");
    }

    @Test
    void deniedByBeforeToolHookCarriesReasonAndTerminate() {
        var hooks = new HookSystem(new LaneState());
        hooks.onBeforeTool("default", ctx ->
            new BeforeToolResult(false, Map.of("reason", "not allowed here"), true));
        var decisions = new java.util.ArrayList<String>();
        var runner = new PiToolRunner("default", registryWith(okTool("echo", "hi")),
            hooks, CTX, null,
            (callId, allowed) -> decisions.add(callId + "=" + allowed));

        var outcome = runner.run(call("echo"));

        assertThat(outcome.isError()).isTrue();
        assertThat(outcome.terminate()).isTrue();
        assertThat(outcome.message().content().toString()).contains("not allowed here");
        // 拒绝也要上报判定：tool.execute 跨度靠它区分「钩子拦下」与「工具自己失败」，
        // 两者在结果消息上都只是 isError=true。
        assertThat(decisions).containsExactly("tc1=false");
    }
}
