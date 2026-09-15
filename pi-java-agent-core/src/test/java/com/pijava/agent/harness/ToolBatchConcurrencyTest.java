package com.pijava.agent.harness;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.pijava.agent.entry.Entry;
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
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevelMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具批次的**真并发**（package B，{@code docs/31 §8.23}）：pi 的
 * {@code executeToolCallsParallel} 用 {@code Promise.all}（{@code agent-loop.ts:547}）把整批
 * 执行票同时开跑。本类钉住移植后仍成立的三件事 ——
 *
 * <ol>
 *   <li><b>同时开跑</b>：批次墙钟是各调用耗时的 <b>max</b>，不是 sum。判据用**互相等待的
 *       闩锁**而不是计时：两个工具体各自 {@code countDown} 再 {@code await} 同一个闩锁，
 *       只有两者真正同时在跑才可能双双通过。串行回退时第一个调用会等到超时并**响亮地失败**，
 *       不会靠运气变绿 —— 这是本包的「有牙齿」判据。</li>
 *   <li><b>两套顺序不同源</b>：{@code tool_execution_end} 是**完成序**（各任务在自己完成时发，
 *       {@code :520-541}），结果消息是**源序**（收束后按 {@code entries} 顺序补发，
 *       {@code :549-554}）。L5 的 S13 是这条的端到端证据，这里做结构级的最小复现。</li>
 *   <li><b>协作式中止</b>：已启动的调用**不被打断**（pi 把 signal 交给工具本体，
 *       {@code :677-718} 没有「abort ⇒ 取消执行」的竞速）—— 中止发生在途中的批次，
 *       在途调用照样跑完并发自己的 end。</li>
 * </ol>
 *
 * <p>并发还引入了一条**串行化义务**：宿主侧的事件消费者（会话事件、TUI/RPC、记录发射）
 * 此前都按单线程写，而 update 回调现在会在各自的工具线程上直呼事件链。
 * {@link PiLaneSink#emit} 是唯一漏斗 —— 见 {@link #concurrentUpdatesSurviveTheEmitFunnel}。</p>
 */
class ToolBatchConcurrencyTest {

    private static final ModelId<?> MODEL = ModelId.of("faux", "test-model");

    /** 互相等待的闩锁：N 个工具体都要先 countDown 再 await 它。 */
    private static CountDownLatch rendezvous(int parties) {
        return new CountDownLatch(parties);
    }

    /**
     * 等到所有同伴都到了才返回。超时**不抛**，而是把事实记在返回值上 —— 断言由调用方做，
     * 失败信息里才带得上「是哪个调用没等到」。
     */
    private static boolean awaitPeers(CountDownLatch latch) {
        try {
            return latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 收集帧标签的 sink。工具线程会并发调用它，所以内部用并发队列 —— 这里测的是
     * {@code PiLoop} 的发射时序，不是 {@link PiLaneSink} 的串行化（后者由 E2E 用例与
     * {@link #concurrentUpdatesSurviveTheEmitFunnel} 覆盖）。
     *
     * <p>{@code releaseOnEnd} 是**完成序的确定性来源**：指定工具名的 end 被记下时放行对应
     * 闩锁，另一个工具体便可在自己的**执行体内**等待「同伴的 end 已经发出」——
     * 「后声明者先完成」于是由闩锁保证，不靠睡眠也不靠调度运气。</p>
     */
    private static final class Frames implements PiLoop.Sink {
        private final ConcurrentLinkedQueue<String> frames = new ConcurrentLinkedQueue<>();
        private final Map<String, CountDownLatch> releaseOnEnd;

        Frames() {
            this(Map.of());
        }

        Frames(Map<String, CountDownLatch> releaseOnEnd) {
            this.releaseOnEnd = releaseOnEnd;
        }

        @Override
        public void emit(PiLoop.Event event) {
            switch (event) {
                case PiLoop.Event.ToolExecutionStart e -> frames.add("start:" + e.toolName());
                case PiLoop.Event.ToolExecutionEnd e -> {
                    frames.add("end:" + e.toolName());
                    var latch = releaseOnEnd.get(e.toolName());
                    if (latch != null) {
                        latch.countDown();
                    }
                }
                case PiLoop.Event.ToolExecutionUpdate e ->
                    frames.add("update:" + e.toolName() + ":" + textOf(e.partialResult()));
                case PiLoop.Event.MessageStart e
                        when e.message() instanceof Message.ToolResultMessage result ->
                    frames.add("result:" + result.toolName());
                default -> { }
            }
        }

        /** update 载荷的真实形状是 {@code ToolResult}（{@code ToolUpdateCallback} 的入参）。 */
        private static String textOf(Object partialResult) {
            if (partialResult instanceof ToolResult<?> result
                    && result.content().getFirst() instanceof ContentBlock.TextContent text) {
                return text.text();
            }
            return String.valueOf(partialResult);
        }

        /** 工具帧（start / end / update / result），按记录序。 */
        List<String> toolFrames() {
            return List.copyOf(frames);
        }

        List<String> ends() {
            return frames.stream().filter(f -> f.startsWith("end:")).toList();
        }

        List<String> updatesFor(String name) {
            return frames.stream().filter(f -> f.startsWith("update:" + name + ":")).toList();
        }
    }

    /**
     * 一个可编程的工具端口：每个调用先在自己的线程上跑 {@code bodies} 里对应的动作，
     * 再流 {@code updatesPerCall} 条更新，最后返回固定结果。
     */
    private record ScriptedTools(Map<String, Runnable> bodies, int updatesPerCall)
            implements PiLoop.ToolRunner {

        @Override
        public PiLoop.Preparation prepare(PiLoop.ToolCall call) {
            return new PiLoop.ToolRunner.CallPrepared(call);
        }

        @Override
        public PiLoop.ToolOutcome execute(PiLoop.Prepared prepared, PiLoop.Sink emit) {
            var call = prepared.call();
            var body = bodies.get(call.toolName());
            if (body != null) {
                body.run();
            }
            for (int i = 1; i <= updatesPerCall; i++) {
                emit.emit(new PiLoop.Event.ToolExecutionUpdate(call.toolCallId(), call.toolName(),
                    call.args(), new ToolResult<>(
                        List.of(new ContentBlock.TextContent("p" + i)), Map.of(), null, false,
                        List.of())));
            }
            var message = new Message.ToolResultMessage(call.toolCallId(), call.toolName(),
                List.of(new ContentBlock.TextContent(call.toolName() + " ok")), false);
            return new PiLoop.ToolOutcome(message, ToolResult.success("ok"), false);
        }
    }

    // ── 剧本 ───────────────────────────────────────────────────────

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

    private static PiLoop.Config config(StreamFn streamFn, PiLoop.ToolRunner tools,
                                        AbortSignal signal) {
        return new PiLoop.Config(MODEL, ModelThinkingLevel.off(), ThinkingLevelMap.empty(),
            ToolExecution.defaultMode(), tools, streamFn, signal, null, null, null, null, null, null);
    }

    private static Message user(String text) {
        return new Message.UserMessage(List.of(new ContentBlock.TextContent(text)));
    }

    // ── 用例：PiLoop 层 ────────────────────────────────────────────

    /**
     * 批次墙钟是 max 不是 sum。串行实现下第一个调用等不到同伴 ⇒ 超时 ⇒ 断言失败，
     * 失败信息指认是哪个调用等的。
     */
    @Test
    void batchRunsItsCallsSimultaneously(TestInfo info) {
        var rendezvous = rendezvous(2);
        var alphaArrived = new AtomicBoolean();
        var betaArrived = new AtomicBoolean();
        var tools = new ScriptedTools(Map.of(
            "alpha", () -> {
                rendezvous.countDown();
                alphaArrived.set(awaitPeers(rendezvous));
            },
            "beta", () -> {
                rendezvous.countDown();
                betaArrived.set(awaitPeers(rendezvous));
            }), 0);
        var frames = new Frames();

        PiLoop.run(List.of(user("go")), Context.of(new ArrayList<>()), config(
            scripted(List.of(
                toolTurn(List.of(new String[] {"c1", "alpha"}, new String[] {"c2", "beta"})),
                textTurn("done"))),
            tools, null), frames);

        assertThat(alphaArrived.get())
            .as("%s：alpha 等到 beta 了 —— 一批调用必须同时在跑，串行会让它等超时",
                info.getDisplayName())
            .isTrue();
        assertThat(betaArrived.get()).as("beta 等到 alpha 了").isTrue();
        // start 是源序（准备循环逐个发），end 是完成序 —— 这一条只钉「两个都在跑」，
        // 完成序由下一个用例专门验证，这里不重复钉别人的顺序。
        assertThat(frames.toolFrames()).startsWith("start:alpha", "start:beta");
        assertThat(frames.ends()).containsExactlyInAnyOrder("end:alpha", "end:beta");
    }

    /**
     * 后声明者先完成 ⇒ 它的 end 先发，而结果消息仍是**源序**。两套顺序不同源
     * （{@code docs/31 §8.23.1}）是包 B 的核心事实：L5 的 S13 把它做成了端到端证据，
     * 这里用闩锁做结构级的确定性复现（不靠睡眠）。
     *
     * <p>「后者先完成」由**观察端放行**保证：{@code faster} 的 end 一旦被记下就放行闩锁，
     * {@code slower} 在自己的执行体内等它。源序发射 end 的实现会让 {@code slower} 等超时并
     * 失败 —— 不是恰好排错。</p>
     */
    @Test
    void laterCallFinishingFirstEndsFirstButMessagesStaySourceOrder() {
        var fasterEnded = new CountDownLatch(1);
        var fasterFinishedFirst = new AtomicBoolean();
        var tools = new ScriptedTools(Map.of(
            // 声明序在前，却等到观察端记下后者的 end 才返回。
            "slower", () -> fasterFinishedFirst.set(awaitPeers(fasterEnded)),
            "faster", () -> { }), 0);
        var frames = new Frames(Map.of("faster", fasterEnded));

        PiLoop.run(List.of(user("go")), Context.of(new ArrayList<>()), config(
            scripted(List.of(
                toolTurn(List.of(new String[] {"c1", "slower"}, new String[] {"c2", "faster"})),
                textTurn("done"))),
            tools, null), frames);

        assertThat(fasterFinishedFirst.get())
            .as("slower 是在 faster 的 end 发出之后才返回的").isTrue();
        assertThat(frames.toolFrames()).containsExactly(
            "start:slower", "start:faster",
            "end:faster",                  // 完成序
            "end:slower",
            "result:slower", "result:faster");   // 结果消息：源序
    }

    /**
     * 中止是**协作式**的：批次执行途中信号被置位，已启动的调用照样跑完并发自己的 end
     * （pi 把 signal 交给工具本体，没有取消竞速）。{@code faster} 先等 {@code slower} 的
     * 执行体真的开跑再置位，因此这条测的确实是「在途调用不被打断」，不掺入「谁先抢到中止检查」
     * 的竞速。
     */
    @Test
    void abortedBatchStillEmitsInFlightEnds() {
        var signal = AbortSignal.create();
        var slowerRunning = new CountDownLatch(1);
        var abortIssued = new CountDownLatch(1);
        var slowerRanToCompletion = new AtomicBoolean();
        var tools = new ScriptedTools(Map.of(
            "slower", () -> {
                slowerRunning.countDown();
                awaitPeers(abortIssued);
                slowerRanToCompletion.set(true);
            },
            "faster", () -> {
                awaitPeers(slowerRunning);
                signal.abort();
                abortIssued.countDown();
            }), 0);
        var frames = new Frames();

        PiLoop.run(List.of(user("go")), Context.of(new ArrayList<>()), config(
            scripted(List.of(
                toolTurn(List.of(new String[] {"c1", "slower"}, new String[] {"c2", "faster"})),
                textTurn("done"))),
            tools, signal), frames);

        assertThat(slowerRanToCompletion.get())
            .as("中止不取消在途调用：slower 的执行体跑到了返回").isTrue();
        assertThat(frames.ends()).containsExactlyInAnyOrder("end:slower", "end:faster");
    }

    /**
     * update 回调在工具线程上直呼事件链，而宿主消费者原先都按单线程写：
     * 四个调用同时各流三条更新时，帧既不丢也不混，且**每个调用自己的更新仍按序**。
     */
    @Test
    void concurrentUpdatesSurviveTheEmitFunnel() {
        var rendezvous = rendezvous(4);
        var bodies = new HashMap<String, Runnable>();
        for (var name : List.of("t1", "t2", "t3", "t4")) {
            bodies.put(name, () -> {
                rendezvous.countDown();
                awaitPeers(rendezvous);
            });
        }
        var frames = new Frames();

        PiLoop.run(List.of(user("go")), Context.of(new ArrayList<>()), config(
            scripted(List.of(
                toolTurn(List.of(new String[] {"c1", "t1"}, new String[] {"c2", "t2"},
                    new String[] {"c3", "t3"}, new String[] {"c4", "t4"})),
                textTurn("done"))),
            new ScriptedTools(bodies, 3), null), frames);

        for (var name : List.of("t1", "t2", "t3", "t4")) {
            assertThat(frames.updatesFor(name))
                .as("%s 的更新条数与次序", name)
                .containsExactly("update:" + name + ":p1", "update:" + name + ":p2",
                    "update:" + name + ":p3");
        }
    }

    /**
     * 宿主链上的事件仍是**串行**的 —— 并发只存在于工具体。
     *
     * <p>pi 的 {@code emit} 是 async、由单线程事件循环逐个 await；Java 侧没有那个循环，
     * 于是「工具线程直呼事件链」这条新通路必须由 {@link PiLaneSink#emit} 收口。
     * 八个调用（各自与同伴会合后）同时各流二十条更新，宿主侧计数器一旦看到两条重叠即为红
     * —— 这是**去掉那把锁就会亮**的判据（见 {@code docs/31 §8.23.6} 的 RE-2）。</p>
     */
    @Test
    void harnessSerializesConcurrentToolEventsForTheHost() {
        int toolCount = 8;
        int updatesPerTool = 20;
        var rendezvous = rendezvous(toolCount);
        var tools = new ArrayList<AgentTool<Map<String, Object>, Void>>();
        var names = new ArrayList<String>();
        for (int i = 0; i < toolCount; i++) {
            var name = "tool" + i;
            names.add(name);
            tools.add(streamingTool(name, rendezvous, updatesPerTool));
        }
        var registry = new ToolRegistry(null);
        tools.forEach(registry::register);
        var calls = new ArrayList<String[]>();
        for (int i = 0; i < toolCount; i++) {
            calls.add(new String[] {"c" + i, names.get(i)});
        }
        var harness = AgentHarness.create(HarnessConfig.builder()
            .streamFn(scripted(List.of(toolTurn(calls), textTurn("done"))))
            .model(MODEL)
            .thinkingLevel(ModelThinkingLevel.off())
            .activeTools(Set.copyOf(tools))
            .toolRegistry(registry)
            .toolExecution(ToolExecution.defaultMode())
            .build());

        var inside = new AtomicInteger();
        var overlaps = new AtomicInteger();
        var updatesSeen = new AtomicInteger();
        PiLoop.Sink host = event -> {
            if (event instanceof PiLoop.Event.ToolExecutionUpdate) {
                if (inside.incrementAndGet() != 1) {
                    overlaps.incrementAndGet();
                }
                updatesSeen.incrementAndGet();
                inside.decrementAndGet();
            }
        };

        harness.prompt("default", "go", List.of(), host);

        assertThat(overlaps.get())
            .as("宿主链上不能有两条事件同时在处理（PiLaneSink.emit 是唯一漏斗）")
            .isZero();
        assertThat(updatesSeen.get()).isEqualTo(toolCount * updatesPerTool);
    }

    /** 与同伴会合后各自流 {@code updates} 条更新的并行工具。 */
    private static AgentTool<Map<String, Object>, Void> streamingTool(
            String name, CountDownLatch rendezvous, int updates) {
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
                rendezvous.countDown();
                awaitPeers(rendezvous);
                for (int i = 1; i <= updates; i++) {
                    onUpdate.onUpdate(new ToolResult<Void>(
                        List.of(new ContentBlock.TextContent("p" + i)), null, null, false,
                        List.of()));
                }
                return new ToolResult<>(List.of(new ContentBlock.TextContent(name + " ok")),
                    null, null, false, List.of());
            }
        };
    }

    // ── 端到端：真实装配（PiToolRunner + PiLaneSink + 注册表） ─────────

    /** 真实注册表里的并行工具：先 countDown、再等同伴。 */
    private static AgentTool<Map<String, Object>, Void> peerWaitingTool(
            String name, CountDownLatch rendezvous, AtomicBoolean arrived) {
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
                rendezvous.countDown();
                arrived.set(awaitPeers(rendezvous));
                return new ToolResult<>(List.of(new ContentBlock.TextContent(name + " ok")),
                    null, null, false, List.of());
            }
        };
    }

    /**
     * 真装配下同样并发 —— 闩锁级性质不依赖测试桩：工具注册表、{@code PiToolRunner}、
     * {@link PiLaneSink} 串起来后，一批调用仍同时在跑，结果消息按源序进转录。
     */
    @Test
    void harnessRunsTheRealToolBatchSimultaneously() {
        var rendezvous = rendezvous(2);
        var alphaArrived = new AtomicBoolean();
        var betaArrived = new AtomicBoolean();
        var alpha = peerWaitingTool("alpha", rendezvous, alphaArrived);
        var beta = peerWaitingTool("beta", rendezvous, betaArrived);
        var registry = new ToolRegistry(null);
        registry.register(alpha);
        registry.register(beta);
        var harness = AgentHarness.create(HarnessConfig.builder()
            .streamFn(scripted(List.of(
                toolTurn(List.of(new String[] {"c1", "alpha"}, new String[] {"c2", "beta"})),
                textTurn("done"))))
            .model(MODEL)
            .thinkingLevel(ModelThinkingLevel.off())
            .activeTools(Set.of(alpha, beta))
            .toolRegistry(registry)
            .toolExecution(ToolExecution.defaultMode())
            .build());

        harness.prompt("go");

        assertThat(alphaArrived.get())
            .as("alpha 等到 beta 了 —— 真装配下同样是并发，闩锁超时即失败").isTrue();
        assertThat(betaArrived.get()).as("beta 等到 alpha 了").isTrue();
        assertThat(toolResultNames(harness)).containsExactly("alpha", "beta");
    }

    /** 转录里的工具结果名字，按出现序（= 源序，pi 在收束后按 entries 顺序补发）。 */
    private static List<String> toolResultNames(AgentHarness harness) {
        return harness.snapshot("default").transcript().stream()
            .filter(Entry.Message.class::isInstance)
            .map(e -> ((Entry.Message) e).message())
            .filter(Message.ToolResultMessage.class::isInstance)
            .map(m -> ((Message.ToolResultMessage) m).toolName())
            .toList();
    }
}
