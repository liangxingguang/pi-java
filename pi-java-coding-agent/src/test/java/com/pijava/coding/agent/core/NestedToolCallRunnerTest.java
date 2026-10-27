package com.pijava.coding.agent.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import com.pijava.agent.harness.PiLoop;
import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ExecutionMode;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolResult;
import com.pijava.agent.tool.ToolUpdateCallback;
import com.pijava.ai.AbortSignal;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.NestedToolCallRecord;

import org.junit.jupiter.api.Test;

/**
 * 批 27（B162/T2）：{@link NestedToolCallRunner}＋{@link NestedCallRecorder} 的
 * pi 对位断言（{@code test/nested-tool-calls.test.ts:53-233} 的 java 镜像）。
 */
class NestedToolCallRunnerTest {

    // ── 夹具 ─────────────────────────────────────────────────────────

    private static AgentTool<Map<String, Object>, Object> fakeTool(String name,
                                                                   ExecutionMode mode) {
        return new AgentTool<>() {
            @Override public String name() {
                return name;
            }

            @Override public String label() {
                return name;
            }

            @Override public String description() {
                return "fake " + name;
            }

            @Override public Map<String, Object> inputSchema() {
                return Map.of("type", "object");
            }

            @Override public ExecutionMode executionMode() {
                return mode;
            }

            @Override public ToolResult<Object> execute(String toolCallId,
                    Map<String, Object> params, AbortSignal signal,
                    ToolUpdateCallback<Object> onUpdate, ToolContext context) {
                return ToolResult.success("ok");
            }
        };
    }

    /** 收集事件＋按名路由结果的假宿主。 */
    private static final class FakeHost implements NestedToolCallRunner.Host {
        final List<AgentTool<?, ?>> tools = new ArrayList<>();
        final List<AgentSessionEvent> events = new ArrayList<>();
        boolean sequential;
        /** 工具名 → 结果工厂（可在内层再调 runner）。 */
        java.util.function.BiFunction<PiLoop.ToolCall, Consumer<Object>, PiLoop.ToolOutcome> behavior;

        @Override public List<AgentTool<?, ?>> getTools() {
            return tools;
        }

        @Override public boolean isSequential() {
            return sequential;
        }

        @Override public synchronized PiLoop.ToolOutcome runToolCall(PiLoop.ToolCall toolCall,
                String parentToolCallId, AbortSignal signal, Consumer<Object> onUpdate) {
            return behavior.apply(toolCall, onUpdate);
        }

        @Override public synchronized void emit(AgentSessionEvent event) {
            events.add(event);
        }

        PiLoop.ToolOutcome outcome(PiLoop.ToolCall call, String text, ToolResult.UsageInfo usage) {
            var result = new ToolResult<>(
                List.of(new ContentBlock.TextContent(text)), null, usage, false, List.of());
            return new PiLoop.ToolOutcome(new Message.ToolResultMessage(
                call.toolCallId(), call.toolName(), result.content(), false), result, false);
        }
    }

    private static NestedToolCallRunner.Options noOptions() {
        return NestedToolCallRunner.Options.none();
    }

    // ── pi :53-98：id 规则＋事件带父 id＋takeRecord 一次 ──────────────

    @Test
    void assignsIdsBelowCallerAndEmitsEventsWithParentId() {
        var host = new FakeHost();
        host.tools.add(fakeTool("echo", new ExecutionMode.Parallel()));
        host.behavior = (call, onUpdate) -> host.outcome(call, "ok", null);
        var runner = new NestedToolCallRunner(host);

        runner.execute("call", "echo", Map.of("a", 1), noOptions());
        runner.execute("call", "echo", Map.of("b", 2), noOptions());

        var startIds = host.events.stream()
            .filter(e -> e instanceof AgentSessionEvent.ToolExecutionStart)
            .map(e -> (AgentSessionEvent.ToolExecutionStart) e).toList();
        assertThat(startIds).extracting(AgentSessionEvent.ToolExecutionStart::toolCallId)
            .containsExactly("call/1", "call/2");
        assertThat(startIds).allSatisfy(e -> assertThat(e.parentToolCallId()).isEqualTo("call"));
        // start/end 成对（pi 的事件序列 [start,end]×2）
        assertThat(host.events).filteredOn(e -> e instanceof AgentSessionEvent.ToolExecutionEnd)
            .hasSize(2);

        var summary = runner.takeRecord("call");
        assertThat(summary.calls().calls())
            .extracting(NestedToolCallRecord::id).containsExactly("call/1", "call/2");
        assertThat(summary.calls().complete()).isTrue();
        // pi :96-97：takeRecord 只能取一次
        assertThat(runner.takeRecord("call")).isNull();
    }

    // ── pi :100-126：深度归并（共享 recorder） ────────────────────────

    @Test
    void recordsCallsOfNestedToolsOnTheModelIssuedCall() {
        var host = new FakeHost();
        host.tools.add(fakeTool("middle", new ExecutionMode.Parallel()));
        host.tools.add(fakeTool("leaf", new ExecutionMode.Parallel()));
        var runner = new NestedToolCallRunner(host);
        host.behavior = (call, onUpdate) -> {
            if (call.toolName().equals("middle")) {
                runner.execute(call.toolCallId(), "leaf", Map.of(), noOptions());
            }
            return host.outcome(call, "ok", null);
        };

        runner.execute("call", "middle", Map.of(), noOptions());

        var summary = runner.takeRecord("call");
        assertThat(summary.calls().calls())
            .extracting(NestedToolCallRecord::id).containsExactly("call/1", "call/1/1");
    }

    // ── pi :128-162：usage 各层汇总 ──────────────────────────────────

    @Test
    void sumsUsageOfNestedResultsAtEveryDepth() {
        var host = new FakeHost();
        host.tools.add(fakeTool("middle", new ExecutionMode.Parallel()));
        host.tools.add(fakeTool("leaf", new ExecutionMode.Parallel()));
        var runner = new NestedToolCallRunner(host);
        host.behavior = (call, onUpdate) -> {
            if (call.toolName().equals("middle")) {
                // middle 自己也是嵌套调用——它的结果 usage（5/1）同计入 recorder
                // （pi :238-239 对每次 execute 都收 outcome.result.usage）；
                // 其嵌套 leaf（10/0）经共享 recorder 归并到 "call" 上。
                runner.execute(call.toolCallId(), "leaf", Map.of(), noOptions());
                return host.outcome(call, "ok", new ToolResult.UsageInfo(5, 1));
            }
            return host.outcome(call, "ok", new ToolResult.UsageInfo(10, 0));
        };

        runner.execute("call", "middle", Map.of(), noOptions());
        runner.execute("call", "leaf", Map.of(), noOptions());

        var summary = runner.takeRecord("call");
        // leaf(10) ＋ middle(5) ＋ 直接 leaf(10) ＝ 25（pi 测试的 input 25 对位）
        assertThat(summary.usage().inputTokens()).isEqualTo(25);
        assertThat(summary.calls().complete()).isTrue();
    }

    @Test
    void usageAbsentWhenNoNestedResultReportsIt() {
        var host = new FakeHost();
        host.tools.add(fakeTool("echo", new ExecutionMode.Parallel()));
        host.behavior = (call, onUpdate) -> host.outcome(call, "ok", null);
        var runner = new NestedToolCallRunner(host);

        runner.execute("call", "echo", Map.of(), noOptions());

        var summary = runner.takeRecord("call");
        assertThat(summary.usage()).isNull();
        assertThat(summary.calls().complete()).isTrue();
    }

    // ── pi :164-187：sequential 工具串行化 ────────────────────────────

    @Test
    void serializesConcurrentCallsToSequentialTools() throws Exception {
        var host = new FakeHost();
        host.tools.add(fakeTool("seq", new ExecutionMode.Sequential()));
        var active = new AtomicInteger();
        var maxActive = new AtomicInteger();
        var entered = new CountDownLatch(3);
        host.behavior = (call, onUpdate) -> {
            int now = active.incrementAndGet();
            maxActive.accumulateAndGet(now, Math::max);
            entered.countDown();
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            active.decrementAndGet();
            return host.outcome(call, "ok", null);
        };
        var runner = new NestedToolCallRunner(host);
        var errors = new ConcurrentLinkedQueue<Throwable>();

        var threads = new ArrayList<Thread>();
        for (int i = 0; i < 3; i++) {
            var t = Thread.ofVirtual().start(() -> {
                try {
                    runner.execute("call", "seq", Map.of(), noOptions());
                } catch (Throwable e) {
                    errors.add(e);
                }
            });
            threads.add(t);
        }
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        for (var t : threads) {
            t.join(5_000);
        }

        assertThat(errors).isEmpty();
        assertThat(maxActive.get()).isEqualTo(1);
        assertThat(runner.takeRecord("call").calls().calls()).hasSize(3);
    }

    // ── pi :190-233：recorder 四条有界规则 ────────────────────────────

    @Test
    void omitsArgumentsOverPerCallLimit() {
        var recorder = new NestedCallRecorder();
        var big = "x".repeat(9 * 1024);
        var record = recorder.start(new PiLoop.ToolCall("c/1", "write",
            Map.of("content", big), false));

        assertThat(record.arguments()).isNull();
        assertThat(record.argumentsBytes())
            .isGreaterThan(NestedCallRecorder.MAX_ARGUMENT_BYTES_PER_CALL);
        assertThat(recorder.snapshot().complete()).isFalse();
    }

    @Test
    void truncatesErrorTextTo500Chars() {
        var recorder = new NestedCallRecorder();
        var record = recorder.start(new PiLoop.ToolCall("c/1", "bash", Map.of(), false));
        recorder.finish(record, true, "e".repeat(600));

        var finished = recorder.snapshot().calls().get(0);
        assertThat(finished.error()).hasSize(NestedCallRecorder.MAX_ERROR_CHARS);
        assertThat(finished.status()).isEqualTo(NestedToolCallRecord.Status.ERROR);
    }

    @Test
    void dropsCallsOver256() {
        var recorder = new NestedCallRecorder();
        for (int i = 0; i < NestedCallRecorder.MAX_CALLS; i++) {
            assertThat(recorder.start(new PiLoop.ToolCall("c/" + i, "t", Map.of(), false)))
                .isNotNull();
        }
        assertThat(recorder.start(new PiLoop.ToolCall("c/257", "t", Map.of(), false))).isNull();
        assertThat(recorder.snapshot().calls()).hasSize(NestedCallRecorder.MAX_CALLS);
        assertThat(recorder.snapshot().complete()).isFalse();
    }

    @Test
    void omitsArgumentsOverTotalBudget() {
        var recorder = new NestedCallRecorder();
        // 每条 7,000B 参数（JSON 后略超 7,000），总额 32KiB ⇒ 第 4 条起只记字节数
        // （pi 测试：4 条 7000B 里只装 4 条边界——java 镜像语义：累计超限即省略）。
        int fitted = 0;
        for (int i = 0; i < 6; i++) {
            var record = recorder.start(new PiLoop.ToolCall("c/" + i, "write",
                Map.of("content", "y".repeat(7_000)), false));
            if (record.arguments() != null) {
                fitted++;
            }
        }
        assertThat(fitted).isLessThan(6);
        assertThat(recorder.snapshot().complete()).isFalse();
    }

    @Test
    void unfinishedCallsMakeSnapshotIncomplete() {
        var recorder = new NestedCallRecorder();
        recorder.start(new PiLoop.ToolCall("c/1", "t", Map.of(), false));

        var snapshot = recorder.snapshot();
        assertThat(snapshot.calls().get(0).status())
            .isEqualTo(NestedToolCallRecord.Status.UNFINISHED);
        assertThat(snapshot.complete()).isFalse();
    }

    @Test
    void emptyAndCompleteRecorderSnapshotsToNull() {
        assertThat(new NestedCallRecorder().snapshot()).isNull();
    }
}
