package com.pijava.agent.harness;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.pijava.agent.compaction.CompactionSettings;
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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 车道消息**工作副本**与两处压缩触发点（{@code docs/31 §4.2}）。
 *
 * <p>对齐 pi 的两层真源：entry 日志是持久真源，{@code AgentState.messages} 是工作副本，
 * 循环请求用的就是它，只在日志变更时重建（{@code agent-session.ts:2357-2359} 的
 * 「写 entry → buildSessionContext → 整体替换」）。本类咬住三条此前没有覆盖的行为：</p>
 *
 * <ul>
 *   <li>阈值压缩在 {@code prepareNextTurn}（pi {@code :542}）触发，且**重建后的消息
 *       经 context 通道交回循环** —— 下一轮请求看到的是压缩后的上下文；</li>
 *   <li>溢出压缩在 {@code agent_end} **之后**跑，不是轮内（pi {@code :1142 → :2132}）；</li>
 *   <li>压缩**从不原地改写消息** —— 它换的是日志，工作副本随之重建。</li>
 * </ul>
 */
class LaneMessagesTest {

    private static final ModelId<?> MODEL = ModelId.of("faux", "test-model");

    /** 每次请求收到的消息列表，按序记录。 */
    private final List<List<Message>> requests = new ArrayList<>();

    // ── 夹具 ────────────────────────────────────────────────

    private static AgentTool<String, Void> echoTool() {
        return new AgentTool<>() {
            @Override public String name() { return "echo"; }
            @Override public String label() { return "echo"; }
            @Override public String description() { return "Echo"; }
            @Override public Map<String, Object> inputSchema() { return Map.of(); }
            @Override public ExecutionMode executionMode() { return new ExecutionMode.Sequential(); }
            @Override public String prepareArguments(Map<String, Object> raw) { return ""; }
            @Override public ToolResult<Void> execute(String id, String params, AbortSignal signal,
                    ToolUpdateCallback<Void> onUpdate, ToolContext ctx) {
                return ToolResult.success(params);
            }
        };
    }

    /** 记录请求入参的脚本流：第 N 次请求用第 N 段脚本。 */
    private StreamFn scripted(List<List<StreamEvent>> scripts) {
        var index = new AtomicInteger();
        return (model, context, options) -> {
            requests.add(List.copyOf(context.messages()));
            var script = scripts.get(index.getAndIncrement());
            return StreamIterator.from(script);
        };
    }

    private static List<StreamEvent> toolTurn(String callId, String name) {
        var done = AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.ToolUseContent(callId, name, Map.of())))
            .withStopReason("tool_use");
        return List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.ToolCallEnd(0, callId, name, Map.of(), done),
            new StreamEvent.StreamDone("tool_use", null, done));
    }

    private static List<StreamEvent> textTurn(String text) {
        var done = AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.TextContent(text)))
            .withStopReason("stop");
        return List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.TextEnd(0, text, done),
            new StreamEvent.StreamDone("stop", null, done));
    }

    /** 一段「报了巨量用量」的响应：溢出检测读的就是这个帧。 */
    private static List<StreamEvent> overflowingTurn(String text) {
        // 3c 的判据读的是**终局助手消息**（pi 的 assistantMessage.usage/.provider/.model）
        // —— 剧本世界没有 AbstractChatApi 的出口盖章，夹具必须自己把 usage 挂上
        // done、并盖当前模型的身份戳，否则 sameModel/C1 与用量直读都看不见这条溢出。
        var done = AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.TextContent(text)))
            .withStopReason("stop")
            .withUsage(new StreamEvent.UsageInfo(500_000, 10, null))
            .withIdentity("faux-api", "faux", "test-model", Instant.now());
        return List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.TextEnd(0, text, done),
            new StreamEvent.UsageInfo(500_000, 10, done),
            new StreamEvent.StreamDone("stop", null, done));
    }

    private AgentHarness harness(StreamFn streamFn, ToolRegistry registry,
                                 CompactionSettings settings, int maxInputTokens) {
        return AgentHarness.create(new HarnessConfig(
            streamFn, MODEL, ModelThinkingLevel.off(), "",
            Set.of(), maxInputTokens, registry, null, null,
            settings, Map.of(),
            com.pijava.ai.http.RetryPolicy.defaultPolicy(),
            com.pijava.telemetry.NoopTelemetryContext.INSTANCE,
            com.pijava.ai.thinking.ThinkingLevelMap.empty(),
            QueueMode.defaultMode(), QueueMode.defaultMode(), ToolExecution.defaultMode(),
            event -> { }));
    }

    /** 一段足够长的 prompt，使 {@code estimateTokens} 越过小窗口的阈值。 */
    private static String longPrompt() {
        return "x".repeat(4_000);
    }

    private static List<String> textsOf(List<Message> messages) {
        return messages.stream()
            .flatMap(m -> m.content().stream())
            .filter(ContentBlock.TextContent.class::isInstance)
            .map(b -> ((ContentBlock.TextContent) b).text())
            .toList();
    }

    private static boolean hasCompactionEntry(AgentHarness harness) {
        return harness.snapshot(AgentHarness.DEFAULT_LANE).transcript().stream()
            .anyMatch(Entry.Compaction.class::isInstance);
    }

    // ── 测试 ────────────────────────────────────────────────

    /**
     * 阈值压缩在 {@code prepareNextTurn} 触发（pi {@code _compactBeforeNextAssistantResponse}），
     * 且**重建后的消息交回循环** —— 第二次请求看到的是压缩后的上下文，而不是原样的历史。
     */
    @Test
    void midRunThresholdCompactionFeedsTheRebuiltContextToTheNextTurn() {
        var registry = new ToolRegistry(null);
        registry.register(echoTool());
        // 窗口 200、留 10 ⇒ 阈值 190 tokens；4000 字符的 prompt 远超它。
        var h = harness(scripted(List.of(toolTurn("c1", "echo"), textTurn("done"))),
            registry, new CompactionSettings(true, 10, 10), 200);

        h.prompt(longPrompt());

        assertThat(requests).as("一次运行里应有两次请求").hasSize(2);
        var firstText = textsOf(requests.get(0));
        var secondText = textsOf(requests.get(1));

        // 第一轮：原始长 prompt，压缩尚未发生。
        assertThat(firstText).anyMatch(t -> t.startsWith("xxxx"));
        assertThat(firstText).noneMatch(t -> t.contains("compacted into the following summary"));

        // 第二轮：压缩摘要已经进上下文 —— 这正是 NextTurnUpdate.context 那条通道。
        assertThat(secondText)
            .as("压缩后的上下文必须整体交回循环，下一轮请求看到它")
            .anyMatch(t -> t.contains("compacted into the following summary"));
        assertThat(hasCompactionEntry(h)).isTrue();
    }

    /**
     * 溢出压缩在**运行收尾**触发（pi {@code _handlePostAgentRun → _checkCompaction}），
     * 不是轮内。这里只有一次请求、一轮结束，压缩仍然发生 —— 说明它挂在运行收尾上。
     */
    @Test
    void overflowCompactionRunsAfterTheRunNotMidTurn() {
        var h = harness(scripted(List.of(overflowingTurn("done"))),
            null, new CompactionSettings(true, 10, 10), 200);

        h.prompt("hello");

        assertThat(requests).as("一次运行只有一次请求").hasSize(1);
        // 这一轮**不是**带着压缩上下文发的 —— 压缩发生在它之后。
        assertThat(textsOf(requests.get(0)))
            .noneMatch(t -> t.contains("compacted into the following summary"));
        assertThat(hasCompactionEntry(h))
            .as("溢出后运行收尾必须落一条 compaction entry")
            .isTrue();
    }

    /**
     * resume 是「日志被首次填充」那一类重建点（pi {@code sdk.ts:376} 的启动恢复）：
     * 播种的日志里若有压缩标记，工作副本必须由它重建，后续请求才看得到摘要。
     *
     * <p>这条走的是 {@code RunLifecycle.seedTranscript} 里的 {@code rebuildLaneMessages} ——
     * 少了它，工作副本会是空的，恢复后的第一次请求只带新 prompt。</p>
     */
    @Test
    void seedingAResumedLogRebuildsTheWorkingCopyFromIt() {
        var h = harness(scripted(List.of(textTurn("done"))), null,
            new CompactionSettings(true, 10, 10), 200);

        var kept = new Message.UserMessage(
            List.of(new ContentBlock.TextContent("kept tail")));
        h.seedTranscript(AgentHarness.DEFAULT_LANE, List.of(
            new Entry.Compaction("cmp-1", 0, null, null, "a summary",
                "u-2", List.of(kept), 100, Map.of(), null),
            new Entry.Message("u-2", 0, "cmp-1", null, kept, null)));

        h.prompt("next");

        var first = textsOf(requests.get(0));
        assertThat(first)
            .as("播种日志里的压缩摘要必须由工作副本重建出来")
            .anyMatch(t -> t.contains("compacted into the following summary")
                && t.contains("a summary"));
        assertThat(first).contains("kept tail", "next");
    }

    // ── 3b：用量优先的估算 + 动态 contextWindow 操作数（docs/31 §8.20） ──

    /** builder 装配的车道：窗口经 resolver 按**当前模型**解析。 */
    private AgentHarness harnessWithWindow(StreamFn streamFn, ModelId<?> model,
                                           CompactionSettings settings,
                                           ToolRegistry registry,
                                           java.util.function.ToIntFunction<ModelId<?>> window) {
        return AgentHarness.create(HarnessConfig.builder()
            .streamFn(streamFn)
            .model(model)
            .compactionSettings(settings)
            .toolRegistry(registry)
            .contextWindow(window)
            .build());
    }

    /**
     * 带 500 用量的工具轮 —— 文本/参数都极短（字符估算约 0），但 provider 报了
     * 巨量用量（3a：partial 的 UsageInfo 直落终局消息）。必须走 tool_use 停因，
     * 运行才会进入第二轮，{@code prepareNextTurn} 的阈值门才有机会开火。
     */
    private static List<StreamEvent> usageToolTurn(long reportedInput) {
        var done = AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.ToolUseContent("c1", "echo", Map.of())))
            .withStopReason("tool_use")
            .withUsage(new StreamEvent.UsageInfo(reportedInput, 0, null));
        return List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.ToolCallEnd(0, "c1", "echo", Map.of(), done),
            new StreamEvent.StreamDone("tool_use", null, done));
    }

    private Entry.Compaction firstCompaction(AgentHarness h) {
        return h.snapshot(AgentHarness.DEFAULT_LANE).transcript().stream()
            .filter(Entry.Compaction.class::isInstance)
            .map(Entry.Compaction.class::cast)
            .findFirst()
            .orElseThrow(() -> new AssertionError("expected a compaction entry"));
    }

    /**
     * 用量优先（pi compaction.ts:215-243）：字符总量远不过线（几个字符 ⇒
     * 个位数 tokens，阈值 190），但最后一条 assistant 报了 500 ⇒ 照压。
     * 3b 之前读 transcript 字符的估算器**永远不会**压这条车道。
     */
    @Test
    void usageBackedEstimateFiresCompactionThatCharsNeverWould() {
        var registry = new ToolRegistry(null);
        registry.register(echoTool());
        var h = harnessWithWindow(
            scripted(List.of(usageToolTurn(500), textTurn("b"))),
            MODEL, new CompactionSettings(true, 10, 10), registry, id -> 200);

        h.prompt("go");

        assertThat(requests).hasSize(2);
        assertThat(textsOf(requests.get(1)))
            .as("500 用量必须把阈值门推过，哪怕字符才几个")
            .anyMatch(t -> t.contains("compacted into the following summary"));
        var marker = firstCompaction(h);
        // tokensBefore 与判据同源（pi prepareCompaction :667）：锚点 usage=500
        // + 锚点**之后**的消息字符（toolResult 几个字符）；锚点之前的 user 不计。
        assertThat(marker.tokensBefore()).isBetween(500, 520);
    }

    /**
     * 窗口 ≤ 0（自定义模型不在目录）时 pi 的两道门**不对称**（3c，docs/31 §8.21）：
     * 轮内门 {@code _compactBeforeNextAssistantResponse}（agent-session.ts:543）有
     * {@code contextWindow <= 0} 守卫 ⇒ 静默；{@code _checkCompaction} 的 T 路
     * （:2230-2256）没有这道守卫，且 {@code shouldCompact = tokens > window - reserve}
     * ⇒ 0 窗口下阈值线是 -10，任何正读数都过线。于是运行收口照样压一条 ——
     * 旧钉「完全不压」是拿轮内门的守卫脑补了 T 路，撤下。
     */
    @Test
    void nonPositiveWindowSkipsInTurnGateButPostRunThresholdFires() {
        var registry = new ToolRegistry(null);
        registry.register(echoTool());
        var h = harnessWithWindow(
            scripted(List.of(usageToolTurn(500), textTurn("b"))),
            MODEL, new CompactionSettings(true, 10, 10), registry, id -> 0);

        h.prompt("go");

        // 轮内门静默 ⇒ 第二次请求不带摘要。
        assertThat(requests).hasSize(2);
        assertThat(textsOf(requests.get(1)))
            .noneMatch(t -> t.contains("compacted into the following summary"));
        // T 路无窗守卫 ⇒ 收口阈值压缩照发（估算锚点 500 > -10）。
        assertThat(hasCompactionEntry(h)).isTrue();
    }

    /**
     * 操作数是**当前模型**的窗口（pi {@code this.model}），不是宿主静态值：
     * 同一脚本，小窗模型压、大窗模型不压。
     */
    @Test
    void thresholdOperandIsTheCurrentModelWindowNotAStatic() {
        java.util.function.ToIntFunction<ModelId<?>> perModel =
            id -> "small".equals(id.modelName()) ? 200 : 1_000_000;
        var smallRegistry = new ToolRegistry(null);
        smallRegistry.register(echoTool());
        var small = harnessWithWindow(
            scripted(List.of(usageToolTurn(500), textTurn("b"))),
            ModelId.of("faux", "small"), new CompactionSettings(true, 10, 10),
            smallRegistry, perModel);
        small.prompt("go");
        assertThat(hasCompactionEntry(small)).isTrue();

        var bigRegistry = new ToolRegistry(null);
        bigRegistry.register(echoTool());
        var big = harnessWithWindow(
            scripted(List.of(usageToolTurn(500), textTurn("b"))),
            ModelId.of("faux", "big"), new CompactionSettings(true, 10, 10),
            bigRegistry, perModel);
        big.prompt("go");
        assertThat(hasCompactionEntry(big))
            .as("百万窗口下 500 用量不过线 —— 窗口必须跟着当前模型走")
            .isFalse();
    }
}
