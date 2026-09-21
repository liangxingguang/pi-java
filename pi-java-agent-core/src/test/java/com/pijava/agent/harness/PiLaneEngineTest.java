package com.pijava.agent.harness;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.record.LaneRecord;
import com.pijava.agent.session.SessionMutation;
import com.pijava.agent.session.SessionState;
import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ExecutionMode;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolRegistry;
import com.pijava.agent.tool.ToolResult;
import com.pijava.agent.tool.ToolUpdateCallback;
import com.pijava.ai.AbortSignal;
import com.pijava.ai.Usage;
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
        return (model, context, options) -> {
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
        // ⚠️ usage 要挂到 partial 上（包 H1 步 6 起夹具的硬要求）：生产的
        // StreamPartialBuilder.emitUsage 先 this.usage = info 再 snapshot() ⇒
        // usage 帧之后的每个 partial 都携带它；终局消息由 fromPartial 投影，
        // UsageRecord 读的正是投影结果（A3 起不再读 sink 的旁路累加器）。
        var done = AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.TextContent(text)))
            .withStopReason("stop")
            .withUsage(new StreamEvent.UsageInfo(11, 7, null, null));
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
            .withStopReason("tool_use")
            .withUsage(new StreamEvent.UsageInfo(5, 3, null, null));
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
            null, Map.of(),
            com.pijava.ai.http.RetryPolicy.defaultPolicy(),
            com.pijava.telemetry.NoopTelemetryContext.INSTANCE, ThinkingLevelMap.empty(),
            QueueMode.defaultMode(), QueueMode.defaultMode(), ToolExecution.defaultMode(),
            event -> { }));
    }

    /** 收集 PiLoop 事件的帧标签。 */
    private static final class Recorder implements PiLoop.Sink {
        // COW：工具帧由 worker 线程发（docs/31 §8.27.7），普通 ArrayList 会丢帧。
        private final List<String> frames = new CopyOnWriteArrayList<>();

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

    /** The lane's records, in emission order. */
    private static List<LaneRecord> recordsOf(AgentHarness h) {
        return h.snapshot(AgentHarness.DEFAULT_LANE).records();
    }

    /** Ids of every {@code OperationStarted}, in emission order. */
    private static List<String> startedIds(List<LaneRecord> records) {
        return records.stream().filter(LaneRecord.OperationStarted.class::isInstance)
            .map(r -> ((LaneRecord.OperationStarted) r).id()).toList();
    }

    /**
     * Every end-to-end run must leave a record log that is self-consistent:
     * starts pair up with finishes in order, each step attempt hangs off a
     * started operation, and each (run, step) attempt series runs 0,1,2,…
     * without gaps.
     *
     * <p>These are the invariants {@code RecordLogValidator} used to check when
     * it folded the log on resume. The fold is retired ({@code docs/30}), so
     * nothing enforces them any more — but the log is still what run summary
     * and the audit trail read, and <b>the new loop had no coverage for any of
     * them</b>: the old {@code LaneStateFoldTest} sentinels all produced their
     * logs through the step chain ({@code peekAction} / {@code executeAction}).</p>
     */
    private static void assertLogIsWellFormed(AgentHarness h) {
        var records = recordsOf(h);
        assertThat(records.stream().filter(LaneRecord.OperationFinished.class::isInstance)
            .map(r -> ((LaneRecord.OperationFinished) r).runId()).toList())
            .as("every started operation is finished, in order")
            .isEqualTo(startedIds(records));

        var started = startedIds(records);
        var seen = new HashMap<String, Integer>();
        for (var record : records) {
            if (record instanceof LaneRecord.StepAttempt step) {
                assertThat(started).as("step attempt %s hangs off a known run", step.id())
                    .contains(step.runId());
                String key = step.runId() + "|" + step.step().value();
                int expected = seen.getOrDefault(key, 0);
                assertThat(step.attempt()).as("%s attempts are consecutive from 0",
                    step.step().value()).isEqualTo(expected);
                seen.put(key, expected + 1);
            }
        }
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

        h.prompt(AgentHarness.DEFAULT_LANE, "hi", List.of(), rec);

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

        h.prompt(AgentHarness.DEFAULT_LANE, "hi", List.of(), new Recorder());

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

        h.prompt(AgentHarness.DEFAULT_LANE, "hi", List.of(), new Recorder());

        var records = recordsOf(h);
        assertThat(records.stream().filter(LaneRecord.StepAttempt.class::isInstance)).hasSize(1);
        var usage = records.stream().filter(LaneRecord.UsageRecord.class::isInstance)
            .map(r -> (LaneRecord.UsageRecord) r).toList();
        assertThat(usage).hasSize(1);
        // A3（包 H1 步 6）起记录读的是**终局消息的 usage**（fromPartial 投影自 partial
        // 携带的 UsageInfo），不再是 sink 的旁路累加器 —— 两分量形状下数值相同。
        assertThat(usage.get(0).usage().input()).isEqualTo(11);
        assertThat(usage.get(0).usage().output()).isEqualTo(7);
        assertThat(usage.get(0).stopReason()).isEqualTo("stop");
    }

    /**
     * T10（docs/42 §8.3）：桩流喂非零全量分解 ⇒ 走完 {@code PiLaneSink} 后
     * {@code UsageRecord.usage()} 四分量与 cost 都非零，且会话账
     * （{@code SessionState.getStats()}，读的就是这条记录）跟着动起来。
     *
     * <p>A3 的钉子 —— 修复前这里是 {@code Usage.of(inputTokens, outputTokens)}，
     * cache/reasoning/cost 恒 0 ⇒ 四分量与 costTotal 两条断言全红（M7 探针同色）。</p>
     */
    @Test
    void fullUsageBreakdownReachesRecordAndSessionLedger() {
        var full = new Usage(100, 20, 40, 10, 6.0, 8.0, 170,
            new Usage.Cost(0.30, 0.15, 0.02, 0.04, 0.51));
        var partial = AssistantMessage.empty();
        var done = AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.TextContent("hi")))
            .withStopReason("stop")
            .withUsage(new StreamEvent.UsageInfo(100, 20, null, full));
        var h = harness(scripted(List.of(List.of(
            new StreamEvent.Start(partial),
            new StreamEvent.UsageInfo(100, 20, done),
            new StreamEvent.StreamDone("stop", null, done)))), null);

        h.prompt(AgentHarness.DEFAULT_LANE, "hi", List.of(), new Recorder());

        var record = recordsOf(h).stream()
            .filter(LaneRecord.UsageRecord.class::isInstance)
            .map(r -> (LaneRecord.UsageRecord) r)
            .findFirst().orElseThrow();
        var u = record.usage();
        assertThat(u.input()).isEqualTo(100);
        assertThat(u.output()).isEqualTo(20);
        assertThat(u.cacheRead()).isEqualTo(40);
        assertThat(u.cacheWrite()).isEqualTo(10);
        assertThat(u.cacheWrite1h()).isEqualTo(6.0);
        assertThat(u.reasoning()).isEqualTo(8.0);
        assertThat(u.totalTokens()).isEqualTo(170);
        assertThat(u.cost().total()).isEqualTo(0.51);

        // 会话账投影：SessionState.applyRecord 从这条记录累加四个量（J14 的存储层
        // 早已能读写全字段 —— 缺的一直只是发射端喂的东西）。committed(...) 是存储层
        // 落账时的重编号原语（JsonlSessionStorage 提交的就是它），seq 须从 1 起。
        var state = new SessionState();
        state.applyMutation(new SessionMutation.Lane(1, record.lane(), null));
        state.applyMutation(new SessionMutation.Record(
            record.committed(2, java.time.Instant.now())));
        var stats = state.getStats();
        assertThat(stats.costTotal()).isEqualTo(0.51);
        assertThat(stats.cachedTokens()).isEqualTo(40);
        assertThat(stats.uncachedTokens()).isEqualTo(110);
        assertThat(stats.totalTokens()).isEqualTo(170);
    }

    /** 一轮工具：结果消息落盘源序、工具记录带结果 entry id、工具真的被执行。 */
    @Test
    void toolTurnAppendsResultsAndEmitsToolRecords() {
        var h = harness(scripted(List.of(
            toolTurn("tc1", "echo", Map.of("x", "1")),
            textTurn("done"))), okTool("echo", "echoed"));

        h.prompt(AgentHarness.DEFAULT_LANE, "run echo", List.of(), new Recorder());

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

        h.prompt(AgentHarness.DEFAULT_LANE, "first", List.of(), new Recorder());
        h.prompt(AgentHarness.DEFAULT_LANE, "second", List.of(), new Recorder());

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

        h.prompt(AgentHarness.DEFAULT_LANE, "go", List.of(), new Recorder());

        var transcript = transcriptOf(h);
        assertThat(transcript).hasSize(4);
        assertThat(((Message.ToolResultMessage) transcript.get(2)).isError()).isTrue();
        assertThat(transcript.get(2).content().toString()).contains("nope");
        assertThat(h.snapshot(AgentHarness.DEFAULT_LANE).operation()).isNull();
    }

    // ── 记录日志的结构不变量 ───────────────────────────────────────

    /** 一轮文本：日志自洽，且车道停稳。 */
    @Test
    void recordLogOfANewLoopTextRunIsWellFormed() {
        var h = harness(scripted(List.of(textTurn("hello"))), null);

        h.prompt(AgentHarness.DEFAULT_LANE, "hi", List.of(), new Recorder());

        assertThat(h.snapshot(AgentHarness.DEFAULT_LANE).operation()).isNull();
        assertLogIsWellFormed(h);
        assertThat(recordsOf(h).stream().filter(LaneRecord.StepAttempt.class::isInstance))
            .singleElement()
            .satisfies(r -> assertThat(((LaneRecord.StepAttempt) r).attempt()).isZero());
    }

    /** 工具轮：日志同样自洽，且工具结果记录指向真实 entry。 */
    @Test
    void recordLogOfANewLoopToolRunIsWellFormed() {
        var h = harness(scripted(List.of(
            toolTurn("tc1", "echo", Map.of("x", "1")),
            textTurn("done"))), okTool("echo", "echoed"));

        h.prompt(AgentHarness.DEFAULT_LANE, "run echo", List.of(), new Recorder());

        assertThat(h.snapshot(AgentHarness.DEFAULT_LANE).operation()).isNull();
        assertLogIsWellFormed(h);
        // 工具轮有两个助手步（tool_use + stop），序号必须 0、1 连续。
        assertThat(recordsOf(h).stream()
            .filter(LaneRecord.StepAttempt.class::isInstance)
            .map(r -> ((LaneRecord.StepAttempt) r).attempt()).toList())
            .containsExactly(0, 1);
        // ToolFinished 的结果 entry 必须真的在车道上；指向不存在的 entry 会让
        // run summary 的失败归因落空。它由 PiLaneSink 在结果消息的 message_end 上回填。
        var transcript = h.snapshot(AgentHarness.DEFAULT_LANE).transcript();
        assertThat(recordsOf(h).stream().filter(LaneRecord.ToolFinished.class::isInstance)
            .map(r -> ((LaneRecord.ToolFinished) r).resultEntryId()).toList())
            .singleElement()
            .satisfies(id -> assertThat(transcript).anyMatch(e -> e.id().equals(id)));
    }
}
