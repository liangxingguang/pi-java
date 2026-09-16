package com.pijava.agent.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.pijava.agent.record.LaneRecord;
import com.pijava.agent.record.UsageCause;
import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ExecutionMode;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolRegistry;
import com.pijava.agent.tool.ToolResult;
import com.pijava.agent.tool.ToolUpdateCallback;
import com.pijava.ai.AbortSignal;
import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ModelThinkingLevel;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code lane.records} 的**跨线程**访问（{@code docs/31 §8.27}）。
 *
 * <p>包 B（{@code docs/31 §8.23}）让工具调用真并发之后，这张审计表多了一个**工具线程**写者：
 * {@code PiToolRunner.execute} 在 worker 线程上跑 {@code after_tool} 钩子，钩子抛异常时
 * {@code HookSystem.recordHookError} 直接往表里 {@code add}
 * （{@code PiToolRunner:172} → {@code HookSystem:200} → {@code :334}）；宿主线程同时在**读**它
 * （{@code SnapshotService} 的 {@code stream()}/{@code copyOf}，由 web/RPC/TUI 的快照请求到达）。
 * 普通 {@code ArrayList} 在这条路径上会丢记录，或让读者抛 {@code ConcurrentModificationException}。</p>
 *
 * <p><b>为什么这条断言有牙齿</b>：八个 {@code after_tool} 钩子先在闩锁上互等（都进了钩子才放行），
 * 由**读者线程**统一放行后才同时抛出 ⇒ 八条虚拟线程的 {@code records.add} 挤在同一个窗口里。
 * 记录条数守恒是**确定性后置条件** —— 丢一条就红，不靠调度运气。顺序批次到不了这里：钩子会
 * 等闩锁超时，{@code hooksArrived} 断言随之失败（这是「并发真的发生了」的判据）。</p>
 */
class LaneRecordsConcurrencyTest {

    private static final ModelId<?> MODEL = ModelId.of("faux", "test-model");

    /** 批大小：够多才能让并发 {@code add} 真的重叠（八条 worker 线程同时写一张表）。 */
    private static final int TOOLS = 8;

    /**
     * 八个并行工具的 {@code after_tool} 钩子同时抛 ⇒ 八条 worker 线程同时写记录表，
     * 而宿主线程正在反复取快照。
     *
     * <p>两个断言各钉一件事：①**记录条数守恒**（COW 之前会丢）；
     * ②**读者不抛**（COW 之前是 {@code ConcurrentModificationException} 或复制期越界）。</p>
     */
    @Test
    void concurrentHookErrorsAllLandInTheRecordLog() {
        var hooksEntered = new CountDownLatch(TOOLS);
        var releaseHooks = new CountDownLatch(1);
        var hooksThrew = new AtomicInteger();
        var hooksArrived = new AtomicBoolean();
        var snapshots = new AtomicInteger();
        var readerFailure = new AtomicReference<Throwable>();

        var tools = new ArrayList<AgentTool<Map<String, Object>, Void>>();
        var calls = new ArrayList<String[]>();
        for (int i = 0; i < TOOLS; i++) {
            var name = "t" + i;
            tools.add(plainTool(name));
            calls.add(new String[] {"c" + i, name});
        }
        var registry = new ToolRegistry(null);
        tools.forEach(registry::register);

        var harness = AgentHarness.create(HarnessConfig.builder()
            .streamFn(scripted(List.of(toolTurn(calls), textTurn("done"))))
            .model(MODEL)
            .thinkingLevel(ModelThinkingLevel.off())
            .activeTools(Set.copyOf(tools))
            .toolRegistry(registry)
            .toolExecution(ToolExecution.defaultMode())
            .build());
        harness.hookSystem().onAfterTool("default", ctx -> {
            hooksEntered.countDown();
            awaitPeers(releaseHooks);
            // 紧挨着 HookSystem.recordHookError 的那次 add —— 读者据此判断窗口何时关。
            hooksThrew.incrementAndGet();
            throw new IllegalStateException("after_tool boom");
        });

        var reader = new Thread(() -> {
            hooksArrived.set(awaitPeers(hooksEntered));
            releaseHooks.countDown();
            // 读数窗口 = 八条钩子错误落表的那一段；出窗口即停，不掺进相位③的转录追加。
            while (hooksThrew.get() < TOOLS && readerFailure.get() == null) {
                try {
                    harness.snapshot("default");
                    snapshots.incrementAndGet();
                } catch (Throwable t) {
                    readerFailure.set(t);
                }
            }
        }, "snapshot-reader");
        reader.start();

        harness.prompt("go");
        join(reader);

        assertThat(hooksArrived.get())
            .as("八个 after_tool 钩子必须同时在跑 —— 顺序批次等不到闩锁，会在这里红")
            .isTrue();
        assertThat(readerFailure.get())
            .as("宿主快照不许因并发写而抛")
            .isNull();
        assertThat(hookErrorRecords(harness))
            .as("八条 worker 线程的 after_tool 错误记录，一条都不许丢")
            .isEqualTo(TOOLS);
        assertThat(snapshots.get()).as("读者确实在写者窗口里读过").isPositive();
    }

    // ── 夹具 ───────────────────────────────────────────────────────

    /** 记录表里 {@code UsageCause.HOOK} 的条数（{@code HookSystem.recordHookError} 的形状）。 */
    private static long hookErrorRecords(AgentHarness harness) {
        return harness.snapshot("default").records().stream()
            .filter(r -> r instanceof LaneRecord.UsageRecord usage
                && usage.cause() == UsageCause.HOOK)
            .count();
    }

    /** 等同伴的闩锁：超时**不抛**，把事实记在返回值上，断言由调用方做（失败信息才带得上现场）。 */
    private static boolean awaitPeers(CountDownLatch latch) {
        try {
            return latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void join(Thread thread) {
        try {
            thread.join(10_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 一个立即返回的并行工具 —— 本夹具要的是它的 {@code after_tool}，不是它的执行。 */
    private static AgentTool<Map<String, Object>, Void> plainTool(String name) {
        return new AgentTool<>() {
            @Override public String name() { return name; }
            @Override public String label() { return name; }
            @Override public String description() { return "test tool " + name; }
            @Override public Map<String, Object> inputSchema() { return Map.of("type", "object"); }
            @Override public ExecutionMode executionMode() { return new ExecutionMode.Parallel(); }
            @Override
            public ToolResult<Void> execute(String toolCallId, Map<String, Object> params,
                                           AbortSignal signal,
                                           ToolUpdateCallback<Void> onUpdate,
                                           ToolContext context) {
                return new ToolResult<>(List.of(new ContentBlock.TextContent(name + " ok")),
                    null, null, false, List.of());
            }
        };
    }

    private static StreamFn scripted(List<List<StreamEvent>> scripts) {
        var index = new AtomicInteger();
        return (model, context, options) -> StreamIterator.from(scripts.get(index.getAndIncrement()));
    }

    /** 一段带多个工具调用的助手响应（{@code stopReason = tool_use}）。 */
    private static List<StreamEvent> toolTurn(List<String[]> calls) {
        var partial = AssistantMessage.empty();
        var blocks = new ArrayList<ContentBlock>();
        var events = new ArrayList<StreamEvent>();
        events.add(new StreamEvent.Start(partial));
        for (int i = 0; i < calls.size(); i++) {
            var id = calls.get(i)[0];
            var name = calls.get(i)[1];
            blocks.add(new ContentBlock.ToolUseContent(id, name, Map.of()));
            events.add(new StreamEvent.ToolCallEnd(i, id, name, Map.of(), partial));
        }
        var done = AssistantMessage.empty().withContent(blocks).withStopReason("tool_use");
        events.add(new StreamEvent.StreamDone("tool_use", null, done));
        return List.copyOf(events);
    }

    private static List<StreamEvent> textTurn(String text) {
        var partial = AssistantMessage.empty();
        var done = AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.TextContent(text)))
            .withStopReason("stop");
        return List.of(
            new StreamEvent.Start(partial),
            new StreamEvent.TextEnd(0, text, done),
            new StreamEvent.StreamDone("stop", null, done));
    }
}
