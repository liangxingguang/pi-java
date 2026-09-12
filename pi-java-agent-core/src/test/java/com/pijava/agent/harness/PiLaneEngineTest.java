package com.pijava.agent.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.record.LaneRecord;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PiLaneEngine} 的端到端行为（{@code docs/28 §5} 第 2 步的验证）。
 *
 * <p>这个测试是**切换驱动前**唯一的证据来源：它把 {@link PiLoop} 接在真实的
 * {@link AgentHarness} 上跑完整一轮，断言车道侧的结果（transcript、记录日志、相位）
 * 与会话侧的下游事件都对得上。</p>
 *
 * <p>最关键的一条是 {@code promptIsNotDuplicated}：{@code ActionExecutor.run} 已经写过
 * 用户 entry，而 {@code PiLoop.run} 还会为同一个 prompt 发一对
 * {@code message_start}/{@code message_end}（pi 的 {@code agent-loop.ts:109-114}）。
 * 抑制逻辑一旦失效，每条用户消息都会在界面上出现两次 —— 这是切到新驱动最容易踩的坑。</p>
 */
class PiLaneEngineTest {

    private static final ModelId<?> MODEL = ModelId.of("faux", "test-model");

    // ── 剧本 ───────────────────────────────────────────────────────

    /** 流式脚本：第 N 次请求使用第 N 个脚本。 */
    private static StreamFn scripted(List<List<StreamEvent>> scripts) {
        var index = new AtomicInteger();
        return (messages, model, options) -> {
            var script = scripts.get(index.getAndIncrement());
            return new StreamIterator() {
                private int i;

                @Override public boolean hasNext() { return i < script.size(); }
                @Override public StreamEvent next() { return script.get(i++); }
                @Override public void close() { }
            };
        };
    }

    /** 纯文本助手响应。 */
    private static List<StreamEvent> textTurn(String text) {
        var partial = AssistantMessage.empty();
        var done = AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.TextContent(text)))
            .withStopReason("stop");
        return List.of(
            new StreamEvent.Start(partial),
            new StreamEvent.TextDelta(0, text, done),
            new StreamEvent.UsageInfo(11, 7, done),
            new StreamEvent.StreamDone("stop", null, done));
    }

    /** 带单个工具调用的助手响应。 */
    private static List<StreamEvent> toolTurn(String callId, String name, Map<String, Object> args) {
        var partial = AssistantMessage.empty();
        var done = AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.ToolUseContent(callId, name, args)))
            .withStopReason("tool_use");
        return List.of(
            new StreamEvent.Start(partial),
            new StreamEvent.ToolCallEnd(0, callId, name, args, done),
            new StreamEvent.UsageInfo(5, 3, done),
            new StreamEvent.StreamDone("tool_use", null, done));
    }

    // ── 夹具 ───────────────────────────────────────────────────────

    /** 一个总是成功的工具，返回固定文本。 */
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

    private static AgentHarness harness(StreamFn sf, AgentTool<?, ?> tool) {
        var registry = new ToolRegistry(null);
        Set<AgentTool<?, ?>> active = Set.of();
        if (tool != null) {
            registry.register(tool);
            active = Set.of(tool);
        }
        var context = new ToolContext(System.getProperty("java.io.tmpdir"), Map.of(),
            new com.pijava.agent.tool.DefaultShellExecutor(),
            new com.pijava.agent.tool.DefaultFileSystem());
        return AgentHarness.create(new HarnessConfig(
            sf, MODEL, ModelThinkingLevel.off(), "system", active, 200_000,
            tool == null ? null : registry, tool == null ? null : context, null,
            DriveMode.MANUAL, null, Map.of(),
            com.pijava.ai.http.RetryPolicy.defaultPolicy(),
            com.pijava.telemetry.NoopTelemetryContext.INSTANCE, ThinkingLevelMap.empty(),
            QueueMode.defaultMode(), QueueMode.defaultMode(), ToolExecution.defaultMode(),
            event -> { }));
    }

    /** 收集 PiLoop 事件的帧标签。 */
    private static final class Recorder implements PiLoop.Sink {
        private final List<String> frames = new ArrayList<>();

        @Override public void emit(PiLoop.Event event) {
            frames.add(switch (event) {
                case PiLoop.Event.AgentStart e -> "agent_start";
                case PiLoop.Event.AgentEnd e -> "agent_end";
                case PiLoop.Event.TurnStart e -> "turn_start";
                case PiLoop.Event.TurnEnd e -> "turn_end";
                case PiLoop.Event.MessageStart e -> "message_start:" + e.message().role();
                case PiLoop.Event.MessageEnd e -> "message_end:" + e.message().role();
                case PiLoop.Event.MessageUpdate e -> "message_update";
                case PiLoop.Event.ToolExecutionStart e -> "tool_execution_start:" + e.toolName();
                case PiLoop.Event.ToolExecutionUpdate e -> "tool_execution_update";
                case PiLoop.Event.ToolExecutionEnd e -> "tool_execution_end:" + e.toolName();
            });
        }
    }

    private static List<Message> transcriptOf(AgentHarness h) {
        return h.snapshot(AgentHarness.DEFAULT_LANE).transcript().stream()
            .filter(Entry.Message.class::isInstance)
            .map(e -> ((Entry.Message) e).message())
            .toList();
    }

    private static List<LaneRecord> recordsOf(AgentHarness h) {
        return h.snapshot(AgentHarness.DEFAULT_LANE).records();
    }

    // ── 用例 ───────────────────────────────────────────────────────

    /**
     * 核心回归：用户消息只能有一条。
     *
     * <p>起手（{@code ActionExecutor.run}）写了用户 entry，{@code PiLoop} 又为同一个
     * prompt 发了一对消息帧。若 {@link PiLaneSink} 的按引用抑制失效，这里会看到两条
     * 用户消息 —— 界面上就是同一句话出现两次。</p>
     */
    @Test
    void promptIsNotDuplicated() {
        var h = harness(scripted(List.of(textTurn("hello"))), null);
        var rec = new Recorder();

        h.piEngine().run(AgentHarness.DEFAULT_LANE, "hi", List.of(), rec);

        var transcript = transcriptOf(h);
        assertThat(transcript).hasSize(2);
        assertThat(transcript.get(0)).isInstanceOf(Message.UserMessage.class);
        assertThat(transcript.get(1)).isInstanceOf(Message.AssistantMessage.class);
        assertThat(transcript.get(1).content().toString()).contains("hello");
        // 下游看到的那一对用户帧仍在（界面依赖它即时回显），只是不重复落盘。
        assertThat(rec.frames).contains("message_start:user", "message_end:user");
    }

    /** 一轮文本：帧序与 pi 一致，且车道收回到 IDLE、操作成对闭合。 */
    @Test
    void textRunClosesTheOperationAndReturnsToIdle() {
        var h = harness(scripted(List.of(textTurn("hello"))), null);

        h.piEngine().run(AgentHarness.DEFAULT_LANE, "hi", List.of(), new Recorder());

        assertThat(h.snapshot(AgentHarness.DEFAULT_LANE).operation()).isNull();
        var records = recordsOf(h);
        var started = records.stream().filter(LaneRecord.OperationStarted.class::isInstance)
            .map(r -> ((LaneRecord.OperationStarted) r).id()).toList();
        var finished = records.stream().filter(LaneRecord.OperationFinished.class::isInstance)
            .map(r -> ((LaneRecord.OperationFinished) r).runId()).toList();
        assertThat(finished).containsExactlyElementsOf(started);
    }

    /** 助手步与用量都要落成记录 —— run summary 的 steps 计数读的就是它。 */
    @Test
    void assistantStepAndUsageAreRecorded() {
        var h = harness(scripted(List.of(textTurn("hello"))), null);

        h.piEngine().run(AgentHarness.DEFAULT_LANE, "hi", List.of(), new Recorder());

        var records = recordsOf(h);
        assertThat(records.stream().filter(LaneRecord.StepAttempt.class::isInstance)).hasSize(1);
        var usage = records.stream().filter(LaneRecord.UsageRecord.class::isInstance)
            .map(r -> (LaneRecord.UsageRecord) r).toList();
        assertThat(usage).hasSize(1);
        // UsageInfo 是独立帧、不属于生命周期事件，只有原始帧旁路才能拿到它。
        assertThat(usage.get(0).usage().input()).isEqualTo(11);
        assertThat(usage.get(0).usage().output()).isEqualTo(7);
        assertThat(usage.get(0).stopReason()).isEqualTo("stop");
    }

    /** 一轮工具：结果消息落盘源序、工具记录带结果 entry id、工具真的被执行。 */
    @Test
    void toolTurnAppendsResultsAndEmitsToolRecords() {
        var h = harness(scripted(List.of(
            toolTurn("tc1", "echo", Map.of("x", "1")),
            textTurn("done"))), okTool("echo", "echoed"));

        h.piEngine().run(AgentHarness.DEFAULT_LANE, "run echo", List.of(), new Recorder());

        var transcript = transcriptOf(h);
        assertThat(transcript).hasSize(4);
        assertThat(transcript.get(1)).isInstanceOf(Message.AssistantMessage.class);
        assertThat(transcript.get(2)).isInstanceOf(Message.ToolResultMessage.class);
        assertThat(transcript.get(2).content().toString()).contains("echoed");
        assertThat(transcript.get(3)).isInstanceOf(Message.AssistantMessage.class);

        var records = recordsOf(h);
        var tools = records.stream().filter(LaneRecord.ToolFinished.class::isInstance)
            .map(r -> (LaneRecord.ToolFinished) r).toList();
        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).toolName()).isEqualTo("echo");
        assertThat(tools.get(0).isError()).isFalse();
        // 结果 entry 必须被指到：ToolFinished 在结果消息的 message_end 上发射，
        // 正是为了拿到这个 id（tool_execution_end 时它还不在）。
        var resultEntryId = tools.get(0).resultEntryId();
        assertThat(resultEntryId).isNotBlank();
        assertThat(h.snapshot(AgentHarness.DEFAULT_LANE).transcript())
            .anyMatch(e -> e.id().equals(resultEntryId));
        // 两步助手 ⇒ 两条 StepAttempt（run summary 的 steps）。
        assertThat(records.stream().filter(LaneRecord.StepAttempt.class::isInstance)).hasSize(2);
    }

    /** 连续两次运行：转录是追加的，且不会留下未闭合的操作。 */
    @Test
    void consecutiveRunsAppendAndNeverLeaveAnOpenOperation() {
        var h = harness(scripted(List.of(textTurn("one"), textTurn("two"))), null);
        var engine = h.piEngine();

        engine.run(AgentHarness.DEFAULT_LANE, "first", List.of(), new Recorder());
        engine.run(AgentHarness.DEFAULT_LANE, "second", List.of(), new Recorder());

        assertThat(transcriptOf(h)).hasSize(4);
        var records = recordsOf(h);
        assertThat(records.stream().filter(LaneRecord.OperationStarted.class::isInstance)).hasSize(2);
        assertThat(records.stream().filter(LaneRecord.OperationFinished.class::isInstance)).hasSize(2);
    }

    /** 未注册的工具也必须以错误结果收场，而不是把异常抛穿驱动。 */
    @Test
    void unknownToolBecomesAnErrorResult() {
        var h = harness(scripted(List.of(
            toolTurn("tc9", "nope", Map.of()),
            textTurn("recovered"))), okTool("echo", "echoed"));

        h.piEngine().run(AgentHarness.DEFAULT_LANE, "go", List.of(), new Recorder());

        var transcript = transcriptOf(h);
        assertThat(transcript).hasSize(4);
        assertThat(((Message.ToolResultMessage) transcript.get(2)).isError()).isTrue();
        assertThat(transcript.get(2).content().toString()).contains("nope");
        assertThat(h.snapshot(AgentHarness.DEFAULT_LANE).operation()).isNull();
    }
}
