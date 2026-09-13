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
 * {@link PiToolRunner} 的错误路径与成功路径（{@code docs/28 §5} 第 2 步的前置件，
 * 两相拆分后按 {@code prepare} / {@code execute} 重新归位）。
 *
 * <p>对应 pi {@code prepareToolCall} 的分支：denied（钩子拒绝）、unavailable（未找到）、
 * 参数非法（{@code ToolArgumentsValidator}）都必须在**准备相**就地定局（immediate）；
 * 执行异常在**执行相**转成错误结果。所有路都返回**错误结果消息**而不是抛出 ——
 * 因为 pi 对它们同样发 {@code tool_execution_start}/{@code tool_execution_end}。</p>
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

    /** 一个总是成功、且带 terminate 提示的工具（pi 的批次停止通道）。 */
    private static AgentTool<String, Void> terminateTool(String name) {
        return new AgentTool<>() {
            @Override public String name() { return name; }
            @Override public String label() { return name; }
            @Override public String description() { return "terminating tool"; }
            @Override public Map<String, Object> inputSchema() { return Map.of(); }
            @Override public ExecutionMode executionMode() { return new ExecutionMode.Sequential(); }
            @Override public String prepareArguments(Map<String, Object> raw) { return "prepared"; }
            @Override public ToolResult<Void> execute(String id, String params, AbortSignal signal,
                    ToolUpdateCallback<Void> onUpdate, ToolContext ctx) {
                return new ToolResult<>(
                    List.of(new ContentBlock.TextContent("done")), null, null, true, List.of());
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

    /** 不收事件的汇：多数测试只关心结局，不关心流式更新。 */
    private static final PiLoop.Sink NO_EMIT = event -> {};

    /** 走完两相：拿到执行票就执行，immediate 就地收尾 —— pi 循环就是这么调度端口的。 */
    private static PiLoop.ToolOutcome runBoth(PiToolRunner runner, PiLoop.ToolCall call) {
        var preparation = runner.prepare(call);
        return preparation instanceof PiLoop.ImmediateOutcome immediate
            ? immediate.outcome()
            : runner.execute((PiLoop.Prepared) preparation, NO_EMIT);
    }

    @Test
    void successfulCallProducesNonErrorResult() {
        var decisions = new java.util.ArrayList<String>();
        var runner = new PiToolRunner("default", registryWith(okTool("echo", "hello")),
            null, CTX, null,
            (callId, allowed) -> decisions.add(callId + "=" + allowed));

        var outcome = runBoth(runner, call("echo"));

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

        var outcome = runBoth(runner, call("nope"));

        assertThat(outcome.isError()).isTrue();
        assertThat(outcome.terminate()).isFalse();
        assertThat(outcome.message().content().toString()).contains("nope");
    }

    @Test
    void throwingToolBecomesErrorResultNotAnException() {
        var runner = new PiToolRunner("default", registryWith(throwingTool("boom")),
            null, CTX, null, null);

        var outcome = runBoth(runner, call("boom"));

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

        var outcome = runBoth(runner, call("echo"));

        assertThat(outcome.isError()).isTrue();
        assertThat(outcome.terminate()).isTrue();
        assertThat(outcome.message().content().toString()).contains("not allowed here");
        // 拒绝也要上报判定：tool.execute 跨度靠它区分「钩子拦下」与「工具自己失败」，
        // 两者在结果消息上都只是 isError=true。
        assertThat(decisions).containsExactly("tc1=false");
    }

    /**
     * 两相拆分本身的行为（pi {@code agent-loop.ts:506-517} 的前提）：被拒与未找到的调用
     * 在准备相就产出 {@code ImmediateOutcome}，可执行的调用拿到 {@code Prepared} 执行票。
     * 若把拒绝塞进执行相，并行批次里它的 end 就会排错位置。
     */
    @Test
    void deniedEndsAtPreparePhaseAndExecutableCallsGetATicket() {
        var hooks = new HookSystem(new LaneState());
        hooks.onBeforeTool("default", ctx ->
            new BeforeToolResult(false, Map.of("reason", "no"), false));
        var runner = new PiToolRunner("default", registryWith(okTool("echo", "hi")),
            hooks, CTX, null, null);

        var blocked = runner.prepare(call("echo"));
        assertThat(blocked).isInstanceOf(PiLoop.ImmediateOutcome.class);
        assertThat(((PiLoop.ImmediateOutcome) blocked).outcome().isError()).isTrue();
        assertThat(runner.prepare(call("nope")))
            .as("未找到同样是准备相定局（pi 的 unavailable）")
            .isInstanceOf(PiLoop.ImmediateOutcome.class);

        var noHooks = new PiToolRunner("default", registryWith(okTool("echo", "hi")),
            null, CTX, null, null);
        var ticket = noHooks.prepare(call("echo"));
        assertThat(ticket).isInstanceOf(PiLoop.Prepared.class);
        assertThat(((PiLoop.Prepared) ticket).call().toolName()).isEqualTo("echo");
        assertThat(noHooks.execute((PiLoop.Prepared) ticket, NO_EMIT).isError()).isFalse();
    }

    // ═══ tool_execution_update：pi executePreparedToolCall 的回调转接（agent-loop.ts:690-704）═══

    /** 执行中流出 N 条更新，并把 update 回调**漏**到外面（模拟执行返回后的泄漏线程）。 */
    private static final class StreamingTool implements AgentTool<String, Void> {
        private final String name;
        private final int updates;
        private final java.util.concurrent.atomic.AtomicReference<ToolUpdateCallback<Void>> leaked =
            new java.util.concurrent.atomic.AtomicReference<>();

        StreamingTool(String name, int updates) {
            this.name = name;
            this.updates = updates;
        }

        java.util.concurrent.atomic.AtomicReference<ToolUpdateCallback<Void>> leaked() {
            return leaked;
        }

        @Override public String name() { return name; }
        @Override public String label() { return name; }
        @Override public String description() { return "streaming test tool"; }
        @Override public Map<String, Object> inputSchema() { return Map.of(); }
        @Override public ExecutionMode executionMode() { return new ExecutionMode.Sequential(); }
        @Override public String prepareArguments(Map<String, Object> raw) { return "prepared"; }
        @Override public ToolResult<Void> execute(String id, String params, AbortSignal signal,
                ToolUpdateCallback<Void> onUpdate, ToolContext ctx) {
            leaked.set(onUpdate);
            if (onUpdate != null) {
                for (int i = 1; i <= updates; i++) {
                    onUpdate.onUpdate(ToolResult.success("partial " + i));
                }
            }
            return ToolResult.success("final");
        }
    }

    @Test
    void toolUpdatesStreamAsEventsCarryingOriginalArgsBeforeTheResult() {
        // pi 的事件 args = prepared.toolCall.arguments（**原始**调用参数，:696）——
        // 钩子改写后的参数进执行，但不进事件。
        var events = new java.util.ArrayList<PiLoop.Event>();
        var hooks = new HookSystem(new LaneState());
        hooks.onBeforeTool("default", ctx ->
            new BeforeToolResult(true, Map.of("q", "rewritten"), false));
        var runner = new PiToolRunner("default",
            registryWith(new StreamingTool("echo", 2)), hooks, CTX, null, null);
        var prepared = runner.prepare(
            new PiLoop.ToolCall("tc1", "echo", Map.of("q", "orig"), false));

        var outcome = runner.execute((PiLoop.Prepared) prepared, events::add);

        var updates = events.stream()
            .filter(PiLoop.Event.ToolExecutionUpdate.class::isInstance)
            .map(PiLoop.Event.ToolExecutionUpdate.class::cast)
            .toList();
        assertThat(updates).as("N 条部分结果 ⇒ N 条 tool_execution_update").hasSize(2);
        assertThat(updates.get(0).toolCallId()).isEqualTo("tc1");
        assertThat(updates.get(0).toolName()).isEqualTo("echo");
        assertThat(updates.get(0).args())
            .as("事件带原始调用参数，不是钩子改写后的").isEqualTo(Map.of("q", "orig"));
        assertThat(partialText(updates.get(0))).isEqualTo("partial 1");
        assertThat(partialText(updates.get(1))).isEqualTo("partial 2");
        assertThat(events).as("更新之外没有别的事件").hasSize(2);
        assertThat(outcome.isError()).isFalse();
    }

    private static String partialText(PiLoop.Event.ToolExecutionUpdate update) {
        var partial = (ToolResult<?>) update.partialResult();
        return ((ContentBlock.TextContent) partial.content().get(0)).text();
    }

    @Test
    void updatesAfterExecuteReturnsAreDiscarded() {
        // pi 的 acceptingUpdates 闩（:688 + finally :716）：执行落定后的回调调用**静默丢弃**。
        // 没有闩，泄漏线程能把已收尾的 toolCallId 再轰出一条事件。
        var events = new java.util.ArrayList<PiLoop.Event>();
        var tool = new StreamingTool("echo", 0);
        var runner = new PiToolRunner("default", registryWith(tool), null, CTX, null, null);
        var prepared = runner.prepare(call("echo"));

        runner.execute((PiLoop.Prepared) prepared, events::add);
        assertThat(events).isEmpty();

        tool.leaked().get().onUpdate(ToolResult.success("late"));
        assertThat(events).as("执行返回后的更新被闩丢弃").isEmpty();
    }

    // ═══ end 载荷 = 完整结果对象（pi :774-782，此前事件只带 details/text）═══

    @Test
    void outcomeResultIsTheWholeToolResult() {
        var runner = new PiToolRunner("default", registryWith(terminateTool("echo")),
            null, CTX, null, null);

        var outcome = runBoth(runner, call("echo"));

        assertThat(outcome.result().content()).hasSize(1);
        assertThat(((ContentBlock.TextContent) outcome.result().content().get(0)).text())
            .isEqualTo("done");
        assertThat(outcome.result().terminate()).isTrue();
        assertThat(outcome.result().details()).isNull();
        assertThat(outcome.result().addedToolNames()).isEmpty();
        assertThat(outcome.terminate()).as("批次门从结果对象派生").isTrue();
    }

    @Test
    void failedExecutionResultMatchesPiCreateErrorToolResultShape() {
        var runner = new PiToolRunner("default", registryWith(throwingTool("boom")),
            null, CTX, null, null);

        var outcome = runBoth(runner, call("boom"));

        // pi 的 createErrorToolResult（:767-772）：单文本块 + **details 是空对象而非 null**。
        // 旧 Java 实现把这棵树丢了（事件只带 details），这个断言把形状钉死。
        assertThat(((ContentBlock.TextContent) outcome.result().content().get(0)).text())
            .isEqualTo("tool exploded");
        assertThat(outcome.result().details()).isEqualTo(Map.of());
        assertThat(outcome.result().usage()).isNull();
        assertThat(outcome.result().terminate()).isFalse();
    }

    // ═══ after_tool：pi 的 finalizeExecutedToolCall（agent-loop.ts:720-764）形状 ═══

    @Test
    void afterToolPatchMergesFieldByFieldAndKeepsTerminate() {
        // pi 的合并是**逐字段 ??**（:745-751）：钩子只改 content，terminate=true 必须活着。
        // 旧的 Java 形状是整体替换 ToolResult —— 改内容会静默吞掉 terminate。
        var hooks = new HookSystem(new LaneState());
        hooks.onAfterTool("default", ctx -> new AfterToolPatch(
            List.of(new ContentBlock.TextContent("redacted")), null, null, null, null));
        var runner = new PiToolRunner("default", registryWith(terminateTool("echo")),
            hooks, CTX, null, null);

        var outcome = runBoth(runner, call("echo"));

        assertThat(outcome.message().content().toString()).contains("redacted");
        assertThat(outcome.terminate()).as("补丁没碰 terminate ⇒ 保留").isTrue();
        assertThat(outcome.isError()).isFalse();
    }

    @Test
    void afterToolPatchCanFlipIsError() {
        // pi :752：isError = afterResult.isError ?? isError —— 钩子可把成功结果标成错误。
        var hooks = new HookSystem(new LaneState());
        hooks.onAfterTool("default", ctx -> new AfterToolPatch(null, null, null, null, true));
        var runner = new PiToolRunner("default", registryWith(okTool("echo", "hi")),
            hooks, CTX, null, null);

        assertThat(runBoth(runner, call("echo")).isError()).isTrue();
    }

    @Test
    void afterToolHookRunsOnFailedExecutionAndSeesIsError() {
        // pi：工具异常在执行段就转成了错误结果（:708-714），收尾段的钩子**照样跑**在它上面
        // —— 钩子能看到 isError=true，还能改写错误文本。旧 Java 实现的 catch 把钩子整个跳过。
        var seen = new java.util.concurrent.atomic.AtomicBoolean();
        var hooks = new HookSystem(new LaneState());
        hooks.onAfterTool("default", ctx -> {
            seen.set(ctx.isError());
            return new AfterToolPatch(
                List.of(new ContentBlock.TextContent("masked")), null, null, null, null);
        });
        var runner = new PiToolRunner("default", registryWith(throwingTool("boom")),
            hooks, CTX, null, null);

        var outcome = runBoth(runner, call("boom"));

        assertThat(seen.get()).as("钩子看到的是执行相的错误结果").isTrue();
        assertThat(outcome.message().content().toString()).contains("masked");
        assertThat(outcome.isError()).as("补丁没翻 isError ⇒ 维持错误").isTrue();
    }

    @Test
    void throwingAfterToolBecomesErrorResult() {
        // pi :754-757：钩子自己抛 ⇒ 收尾段转错误结果（内容换异常文本），异常不出端口。
        var hooks = new HookSystem(new LaneState());
        hooks.onAfterTool("default", ctx -> { throw new RuntimeException("after boom"); });
        var runner = new PiToolRunner("default", registryWith(okTool("echo", "hi")),
            hooks, CTX, null, null);

        var outcome = runBoth(runner, call("echo"));

        assertThat(outcome.isError()).isTrue();
        assertThat(outcome.message().content().toString()).contains("after boom");
    }

    @Test
    void throwingBeforeToolBecomesImmediateErrorResult() {
        // pi prepareToolCall 的 catch（:668-673）：before_tool 抛异常 ⇒ immediate 错误结果，
        // **不是**「吞掉当放行」。异常由 HookSystem 记账后重抛，转换点在 prepare 的 catch。
        var hooks = new HookSystem(new LaneState());
        hooks.onBeforeTool("default", ctx -> { throw new RuntimeException("before boom"); });
        var runner = new PiToolRunner("default", registryWith(okTool("echo", "hi")),
            hooks, CTX, null, null);

        var preparation = runner.prepare(call("echo"));

        assertThat(preparation).isInstanceOf(PiLoop.ImmediateOutcome.class);
        var outcome = ((PiLoop.ImmediateOutcome) preparation).outcome();
        assertThat(outcome.isError()).isTrue();
        assertThat(outcome.message().content().toString()).contains("before boom");
    }
}
