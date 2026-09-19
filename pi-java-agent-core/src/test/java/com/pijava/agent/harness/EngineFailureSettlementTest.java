package com.pijava.agent.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.record.LaneRecord;
import com.pijava.agent.record.OperationOutcome;
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
 * B5 第 2 步（{@code docs/31 §8.36.5}）的**引擎侧**守卫：一个 pass 的驱动**抛出**时，
 * 引擎照 pi {@code Agent.handleRunFailure}（{@code agent.ts:506-525}）把异常压成
 * 一条失败助手消息，并照常走 {@code message_start → message_end → turn_end → agent_end}。
 *
 * <p><b>为什么注入点是工具、不是 provider</b>：{@code AbstractChatApi} 会把 provider
 * 抛出的 {@code Throwable} 兜成事件（{@code :104}）⇒ 从 provider 注入根本到不了引擎的
 * catch。工具体在 {@code PiToolRunner} 里只被 {@code catch (Exception)} 保护，
 * {@code Error} 原样穿出 ⇒ 穿 {@code PiLoopTools.executeSequential} ⇒ 穿
 * {@code PiLoop.run} ⇒ 落在引擎的 {@code catch (Throwable)}。</p>
 *
 * <p><b>为什么钉的是 Sequential 工具</b>：顺序路径的工具体在**引擎线程**上跑，且每条
 * 工具结果消息在该调用收尾时立即进工作副本（{@code PiLoopTools:96-113}）—— 后者是夹具 3
 * 能续跑的前提（见下）。</p>
 *
 * <p><b>本文件不含宿主侧断言</b>（future 落定 / 会话事件面 / 退出码）—— 那些只有
 * {@code SessionRunner} 能看到，住在 {@code pi-java-coding-agent} 的
 * {@code SessionFailurePathTest}。{@code PiLoop.Event} 只在引擎边界可见，所以形状断言在
 * 这里（这是对设计稿 §8.36.6「夹具按注入点分」的细化，理由见实施记录）。</p>
 */
class EngineFailureSettlementTest {

    private static final ModelId<?> MODEL = ModelId.of("faux", "settle-model");

    /** 恒抛 {@code AssertionError} 的工具：{@code Error} 不在 {@code catch (Exception)} 的射程内。 */
    private static AgentTool<String, Void> thrower(String name, String message) {
        return new AgentTool<>() {
            @Override public String name() { return name; }
            @Override public String label() { return name; }
            @Override public String description() { return "Always throws"; }
            @Override public Map<String, Object> inputSchema() { return Map.of(); }
            @Override public ExecutionMode executionMode() { return new ExecutionMode.Sequential(); }
            @Override public String prepareArguments(Map<String, Object> raw) { return ""; }
            @Override public ToolResult<Void> execute(String id, String params, AbortSignal signal,
                    ToolUpdateCallback<Void> onUpdate, ToolContext ctx) {
                throw new AssertionError(message);
            }
        };
    }

    private static AgentTool<String, Void> echoer(String name) {
        return new AgentTool<>() {
            @Override public String name() { return name; }
            @Override public String label() { return name; }
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

    /** 一轮只发工具调用（源序），不带文本。 */
    private static StreamFn toolCallStreamFn(List<ContentBlock.ToolUseContent> calls) {
        var partial = AssistantMessage.empty()
            .withContent(new ArrayList<ContentBlock>(calls))
            .withStopReason("tool_use");
        var events = new ArrayList<StreamEvent>();
        events.add(new StreamEvent.Start(AssistantMessage.empty()));
        for (int i = 0; i < calls.size(); i++) {
            events.add(new StreamEvent.ToolCallStart(i, AssistantMessage.empty()));
            events.add(new StreamEvent.ToolCallEnd(i, calls.get(i).id(), calls.get(i).name(),
                calls.get(i).arguments(), partial));
        }
        events.add(new StreamEvent.StreamDone("tool_use", null, partial));
        return (model, context, options) -> StreamIterator.from(events);
    }

    /** 一轮纯文本正常收尾。 */
    private static StreamFn textStreamFn(String text) {
        var done = AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.TextContent(text)))
            .withStopReason("stop");
        return (model, context, options) -> StreamIterator.from(List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.TextStart(0, AssistantMessage.empty()),
            new StreamEvent.TextDelta(0, text, done),
            new StreamEvent.TextEnd(0, text, done),
            new StreamEvent.StreamDone("stop", null, done)));
    }

    /** 按调用序号取剧本（越界后停在最后一条）：退避重试的多 pass 剧本靠它。 */
    private static StreamFn sequence(List<StreamFn> script) {
        var index = new AtomicInteger();
        return (model, context, options) -> script.get(
            Math.min(index.getAndIncrement(), script.size() - 1)).stream(model, context, options);
    }

    private static AgentHarness harness(StreamFn streamFn, ToolRegistry registry,
            Set<AgentTool<?, ?>> activeTools, RetryObserver observer, RetrySettings retry) {
        return AgentHarness.create(HarnessConfig.builder()
            .streamFn(streamFn)
            .model(MODEL)
            .thinkingLevel(ModelThinkingLevel.off())
            .systemPrompt("")
            .activeTools(activeTools)
            .maxInputTokens(200_000)
            .toolRegistry(registry)
            .toolExecution(ToolExecution.defaultMode())
            .retrySettings(() -> retry)
            .retryObserver(observer)
            .build());
    }

    /** 合成消息的判别形状：pi 的失败消息 = 空文本块 + 失败 stopReason（{@code agent.ts:507-521}）。 */
    private static boolean isSynthesized(Message message) {
        return message instanceof Message.AssistantMessage assistant
            && assistant.content().equals(List.of(new ContentBlock.TextContent("")));
    }

    private static List<PiLoop.Event.TurnEnd> turnEnds(ArrayList<PiLoop.Event> events) {
        return events.stream().filter(PiLoop.Event.TurnEnd.class::isInstance)
            .map(PiLoop.Event.TurnEnd.class::cast).toList();
    }

    private static List<PiLoop.Event.AgentEnd> agentEnds(ArrayList<PiLoop.Event> events) {
        return events.stream().filter(PiLoop.Event.AgentEnd.class::isInstance)
            .map(PiLoop.Event.AgentEnd.class::cast).toList();
    }

    // ═══════════════════════════════════════════════════════════
    // 夹具 1（C 组）：引擎内 Error 的合成与形状
    // ═══════════════════════════════════════════════════════════

    /**
     * C1/C2：合成的失败消息形状（{@code agent.ts:507-521} 逐字）＋ 四事件各一条。
     *
     * <p>「各一条」的读法：本夹具只有一次 pass、且工具批次在发 {@code turn_end} 之前就炸了
     * ⇒ 引擎流里 {@code turn_end}／{@code agent_end} **总数**都是 1，都是合成的那条。</p>
     */
    @Test
    void engineThrowSynthesizesFailureMessage() {
        var registry = new ToolRegistry(null);
        registry.register(thrower("boom", "boom"));
        var h = harness(sequence(List.of(toolCallStreamFn(List.of(
                new ContentBlock.ToolUseContent("c1", "boom", Map.of()))))),
            registry, Set.<AgentTool<?, ?>>of(thrower("boom", "boom")),
            RetryObserver.NOOP, new RetrySettings(false, 3, 1, null));

        var events = new ArrayList<PiLoop.Event>();
        h.prompt(h.laneName(), "go", List.of(), events::add);

        var synthesized = events.stream()
            .filter(PiLoop.Event.MessageStart.class::isInstance)
            .map(e -> ((PiLoop.Event.MessageStart) e).message())
            .filter(EngineFailureSettlementTest::isSynthesized)
            .toList();
        assertThat(synthesized).as("message_start(失败助手) 恰一条").hasSize(1);
        var failure = (Message.AssistantMessage) synthesized.get(0);

        var failureEnds = events.stream()
            .filter(PiLoop.Event.MessageEnd.class::isInstance)
            .map(e -> ((PiLoop.Event.MessageEnd) e).message())
            .filter(failure::equals)
            .toList();
        assertThat(failureEnds).as("message_end(失败助手) 恰一条").hasSize(1);

        var turns = turnEnds(events);
        assertThat(turns).as("turn_end 恰一条（工具批次没跑到 PiLoop 自己的收尾）").hasSize(1);
        assertThat(turns.get(0).message()).isEqualTo(failure);
        assertThat(turns.get(0).toolResults()).isEmpty();

        var ends = agentEnds(events);
        assertThat(ends).as("agent_end 恰一条").hasSize(1);
        assertThat(ends.get(0).messages()).containsExactly(failure);

        // C1：字段（pi 的 `{ content: [], stopReason, ...identity, usage: EMPTY_USAGE }`）
        assertThat(failure.content()).containsExactly(new ContentBlock.TextContent(""));
        assertThat(failure.usage().input()).isZero();
        assertThat(failure.usage().output()).isZero();
        assertThat(failure.provider()).as("identity 退到车道模型（FauxProvider 不挂 api 三元）")
            .isEqualTo(MODEL.provider());
        assertThat(failure.model()).isEqualTo(MODEL.modelName());
        assertThat(failure.deferred()).isNull();
        assertThat(failure.rawStopReason()).isNull();

        // C2：错误文本 = 抛出者自己的 message（pi `error.message`）
        assertThat(failure.stopReason()).isEqualTo("error");
        assertThat(failure.errorMessage()).isEqualTo("boom");
    }

    /**
     * C3/C4：合成消息进了转录（日志 + 工作副本），且这次运行结算为失败。
     *
     * <p>结论链：{@code PiLaneSink.onMessageEnd} 把消息推进工作副本、按 present 去重后落盘，
     * 并以 {@code lane.partial} 同步出 {@code newestOwn} ⇒
     * {@code HarnessUtils.determineOutcome} 得 "error" ⇒ {@code OperationFinished} 记
     * {@link OperationOutcome#FAILED}。</p>
     */
    @Test
    void synthesizedFailureMessageEntersTranscriptAndSettlesRunAsFailed() {
        var registry = new ToolRegistry(null);
        registry.register(thrower("boom", "boom"));
        var h = harness(sequence(List.of(toolCallStreamFn(List.of(
                new ContentBlock.ToolUseContent("c1", "boom", Map.of()))))),
            registry, Set.<AgentTool<?, ?>>of(thrower("boom", "boom")),
            RetryObserver.NOOP, new RetrySettings(false, 3, 1, null));

        h.prompt(h.laneName(), "go", List.of(), null);

        var transcript = h.snapshot(h.laneName()).transcript();
        var assistants = transcript.stream()
            .filter(Entry.Message.class::isInstance)
            .map(e -> ((Entry.Message) e).message())
            .filter(Message.AssistantMessage.class::isInstance)
            .toList();
        assertThat(assistants).as("助手消息：工具调用那条 + 合成的一条").hasSize(2);
        assertThat(assistants.get(1)).isInstanceOfSatisfying(Message.AssistantMessage.class,
            a -> {
                assertThat(a.stopReason()).isEqualTo("error");
                assertThat(a.errorMessage()).isEqualTo("boom");
            });

        var last = transcript.get(transcript.size() - 1);
        assertThat(last).isInstanceOf(Entry.Message.class);
        assertThat(((Entry.Message) last).message())
            .isEqualTo(assistants.get(1));

        var finished = h.snapshot(h.laneName()).records().stream()
            .filter(LaneRecord.OperationFinished.class::isInstance)
            .map(LaneRecord.OperationFinished.class::cast)
            .toList();
        assertThat(finished).hasSize(1);
        assertThat(finished.get(0).outcome()).isEqualTo(OperationOutcome.FAILED);
    }

    // ═══════════════════════════════════════════════════════════
    // 夹具 3：catch 必须住在重试 `while` 体内
    // ═══════════════════════════════════════════════════════════

    /**
     * D1/D2/D3：合成消息的 {@code errorMessage} 命中瞬断白名单时，**它必须进得了
     * post-run 重试判定** —— 而 {@code _handlePostAgentRun} 读的正是
     * {@code _lastAssistantMessage}（{@code agent-session.ts:1116-1123}），由
     * {@code message_end} 监听器在**这条合成消息**上赋值。⇒ 把引擎的 catch 放到
     * {@code while} 之外，合成的失败消息就进不了判定，重试环不启动。
     *
     * <p>剧本的第二个工具（{@code echo}）是**必需的**、不是装饰：{@code _prepareRetry}
     * 摘掉工作副本尾部的失败助手（{@code agent-session.ts:2941-2945}）之后，
     * {@code continue()} 要求尾部**不是**助手消息（{@code agent.ts:372}）—— 批次里若只有
     * 抛错的工具，摘完尾巴停在 {@code tool_use} 助手消息上，续跑会抛
     * "Cannot continue from message role: assistant"。pi 自己也是这样（同一段代码），
     * 所以这里不是绕开缺陷，而是复刻 pi 的可达形状：顺序路径会先落下 echo 的结果消息。</p>
     */
    @Test
    void retryableFailureTextEntersPostRunRetryDecision() {
        var registry = new ToolRegistry(null);
        registry.register(echoer("echo"));
        registry.register(thrower("boom", "connection refused"));
        var retries = new ArrayList<String>();
        var observed = new RetryObserver() {
            @Override public void onAutoRetryStart(int attempt, int maxAttempts, long delayMs,
                                                   String errorMessage) {
                retries.add("start|" + attempt + "|" + maxAttempts + "|" + errorMessage);
            }
            @Override public void onAutoRetryEnd(boolean success, int attempt, String finalError) {
                retries.add("end|" + success + "|" + attempt + "|" + finalError);
            }
        };
        var h = harness(sequence(List.of(
                toolCallStreamFn(List.of(
                    new ContentBlock.ToolUseContent("c1", "echo", Map.of()),
                    new ContentBlock.ToolUseContent("c2", "boom", Map.of()))),
                textStreamFn("recovered"))),
            registry,
            Set.<AgentTool<?, ?>>of(echoer("echo"), thrower("boom", "connection refused")),
            observed, new RetrySettings(true, 3, 1, null));

        h.prompt(h.laneName(), "go", List.of(), null);

        // D1：重试环真的起来了，且错误文本来自**合成消息**（不是 provider 的事件）
        assertThat(retries).containsExactly(
            "start|1|3|connection refused", "end|true|1|null");

        // D2：失败消息留在转录（日志）里，但**只摘工作副本尾**
        var transcript = h.snapshot(h.laneName()).transcript();
        var failed = transcript.stream()
            .filter(Entry.Message.class::isInstance)
            .map(e -> ((Entry.Message) e).message())
            .filter(Message.AssistantMessage.class::isInstance)
            .map(Message.AssistantMessage.class::cast)
            .filter(a -> "connection refused".equals(a.errorMessage()))
            .toList();
        assertThat(failed).as("失败助手留在日志").hasSize(1);

        // D3：第二 pass 正常收尾 ⇒ 终局是成功
        var lastAssistant = transcript.stream()
            .filter(Entry.Message.class::isInstance)
            .map(e -> ((Entry.Message) e).message())
            .filter(Message.AssistantMessage.class::isInstance)
            .map(Message.AssistantMessage.class::cast)
            .reduce((first, second) -> second).orElseThrow();
        assertThat(lastAssistant.stopReason()).isEqualTo("stop");
        assertThat(h.snapshot(h.laneName()).records().stream()
            .filter(LaneRecord.OperationFinished.class::isInstance)
            .map(LaneRecord.OperationFinished.class::cast)
            .reduce((first, second) -> second).orElseThrow().outcome())
            .isEqualTo(OperationOutcome.COMPLETED);
    }
}
