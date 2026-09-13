package com.pijava.agent.harness;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.hook.RequestContext;
import com.pijava.agent.hook.ResponseContext;
import com.pijava.agent.record.LaneRecord;
import com.pijava.agent.record.ReplayKind;
import com.pijava.agent.record.StepKind;
import com.pijava.agent.record.UsageCause;
import com.pijava.ai.Usage;
import com.pijava.ai.message.Message;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.telemetry.SpanOptions;
import com.pijava.telemetry.TelemetrySpan;

/**
 * 把 {@link PiLoop} 的事件翻译成**车道状态**：创建 entry、写记录日志、维护
 * {@code lane.partial} 与 {@code lane.newestOwn}。
 *
 * <p><b>为什么在 agent-core 而不是 coding-agent</b>：这些操作全部落在
 * {@link LaneState} 上，而它是包内可见的。<b>这是 {@code docs/28 §5.1} 的一处更正</b>
 * —— 原文假定桥接器只做事件转发，实测发现 {@code ContextAssembler}、
 * {@code CompactionExecutor} 与记录日志的九处发射点都绑定车道，桥接器必须与车道同侧。</p>
 *
 * <p><b>职责边界</b>：本类只做「事件 → 车道」。面向会话层的事件
 * （{@code AgentSessionEvent}、流观察者）由 {@code PiSessionBridge} 在
 * {@code coding-agent} 侧独立承担，两者经构造参数 {@code downstream} 串联。</p>
 *
 * <p><b>与旧路径的对应</b>：旧路径把「创建 entry」分散在
 * {@code AssistantStreamExecutor:184-198}（助手）、{@code ToolExecutionPipeline}
 * （工具结果）与 {@code ActionExecutor.injectUserMessages}（steer）三处；本类按 pi 的
 * 结构统一到 {@code message_end} 一个点上 —— 这正是 {@code docs/28} 选项 C 所要求的
 * 「等价性由结构保证」。</p>
 */
final class PiLaneSink implements PiLoop.Sink {

    /** 只用来量参数规模的 JSON 序列化器。 */
    private static final ObjectMapper ARGS_MAPPER = new ObjectMapper();

    private final ExecutionContext ctx;
    private final String laneName;
    private final PiLoop.Sink downstream;

    /**
     * 驱动开始前已在 transcript 里的消息（按**引用**判等）。
     *
     * <p>引擎用 {@code ActionExecutor.run()} 完成车道起手 —— 用户 entry 在那时已落盘，
     * 而 {@link PiLoop#run} 还会为同一个 prompt 发一对
     * {@code message_start}/{@code message_end}（pi 的 {@code agent-loop.ts:109-114}
     * 就是这么发的）。不抑制的话用户消息会被写两遍。</p>
     */
    private final Set<Message> alreadyPresent;

    /** 本次工具调用的终止标记与起算时刻，由 {@link PiToolRunner} 回填。 */
    private final Map<String, Boolean> toolTerminate = new HashMap<>();
    private final Map<String, Long> toolStartNanos = new HashMap<>();

    /**
     * 本次工具调用的 {@code before_tool} 判定（pi-java 可观测性层：被钩子拒绝与
     * 「工具自己失败」在结果消息上都只是 {@code isError=true}，跨度需要分开）。
     */
    private final Map<String, Boolean> toolAllowed = new HashMap<>();

    /** 本次工具调用的 {@code tool.execute} 跨度，在 {@link #noteToolStart} 开、结果消息处关。 */
    private final Map<String, TelemetrySpan> toolSpans = new HashMap<>();

    /**
     * 本轮的**工具批次**成员（按 start 顺序）。
     *
     * <p>{@code ToolExecutionPipeline} 曾按批次整体处理，所以跨度带 {@code toolIndex} /
     * {@code batchSize}；pi 的驱动是逐调用经过端口，批次形状只在
     * {@code PiLoopTools} 里。这里用「助手消息落定后清空」重建同一个批次 ——
     * pi 的顺序是 message_end → 全部 start → 各自 end，所以一个助手消息之后的全部 start
     * 恰是同一批。</p>
     */
    private final List<String> batchCallIds = new ArrayList<>();

    /** 本次请求实际发给 provider 的消息数，由引擎的 {@code transformContext} 回填。 */
    private int assembledMessageCount;

    /**
     * 本次 run 的系统提示，由引擎在 run 起点装进 {@code Context} 时同步回填。
     *
     * <p>它不在消息列表里（pi 的 {@code Message} 没有 system 角色），但
     * {@code before_request} 钩子要看完整的请求，所以单独带一份。</p>
     */
    private String systemPrompt;

    /** 本次助手流的用量：pi-java 的 {@link StreamEvent.UsageInfo} 是独立帧，只能旁路累计。 */
    private long inputTokens;
    private long outputTokens;

    /** 本次请求的 step 序号（每次助手流 +1）。 */
    private int stepIndex;

    /** 本轮 {@code llm.request} 遥测跨度，由 {@link #beginRequest} 打开、{@link #endRequest} 关闭。 */
    private TelemetrySpan llmSpan;
    private long llmStartNanos;

    /**
     * 本次运行最后一个落定的助手消息（pi {@code _lastAssistantMessage}，
     * {@code agent-session.ts:636,691}；package 3c）。post-run 的压缩检查读它 ——
     * 事件侧跟踪，**不是**对工作副本的扫描；一轮没有助手消息则为 {@code null}。
     * 每个 PiLaneSink 只活一条 pass（pi 读后即清 ≙ 我们换 sink），故无需显式清空。
     */
    private Message.AssistantMessage lastAssistant;

    /** pi {@code const msg = this._lastAssistantMessage}（{@code :1117}）的读取端。 */
    Message.AssistantMessage lastAssistant() {
        return lastAssistant;
    }

    PiLaneSink(ExecutionContext ctx, String laneName, Set<Message> alreadyPresent,
               PiLoop.Sink downstream) {
        this.ctx = ctx;
        this.laneName = laneName;
        this.alreadyPresent = alreadyPresent;
        this.downstream = downstream;
    }

    /** 由引擎的 {@code transformContext} 回填，供 {@code StepAttempt.messageCount} 使用。 */
    void assembledMessageCount(int count) {
        this.assembledMessageCount = count;
    }

    /** 由引擎在 run 起点回填：系统提示不进消息列表，但 {@code before_request} 要看到它。 */
    void systemPrompt(String prompt) {
        this.systemPrompt = prompt;
    }

    // ═══════════════════════════════════════════════════════════
    // 一轮请求的开销侧：钩子 + 遥测（对齐 AssistantStreamExecutor:67-95）
    // ═══════════════════════════════════════════════════════════

    /**
     * 一轮 provider 请求开始：发 {@code before_request} 钩子并打开 {@code llm.request} 跨度。
     *
     * <p>这些原本长在 {@code AssistantStreamExecutor.execute()} 里 —— 它们是**执行步**的
     * 开销，不是驱动循环的，所以 {@link PiLoop} 不带它们。切换驱动时必须显式搬过来，
     * 否则 {@code before_request} 钩子静默失效、{@code llm.request} 跨度消失
     * （{@code PayloadRecordingStreamFnTest} 与 {@code ModelSwitchRealReproTest} 抓的就是这个）。</p>
     */
    void beginRequest(LaneState lane, List<Message> messages) {
        ctx.hookSystem().fireBeforeRequest(laneName,
            new RequestContext(laneName, lane.runId, systemPrompt, messages));
        llmStartNanos = System.nanoTime();
        var parent = lane.runSpan != null ? lane.runSpan : ctx.telemetry();
        llmSpan = parent.openSpan(new SpanOptions("llm.request", Map.of(
            "attempt", stepIndex,
            "model", RunSpanFactory.modelLabel(ctx.model().get()),
            "messageCount", messages.size(),
            "toolCount", toolCount(),
            "thinking", RunSpanFactory.thinkingLabel(ctx.thinkingLevel().get()))));
        ctx.telemetry().pushCurrent(llmSpan);
    }

    /**
     * 一轮请求结束：发 {@code after_response}、关跨度、记指标。
     *
     * <p>3c 之前这里还挂着「最后一请求停因」的旁路字段供运行后的溢出判定；
     * 现在溢出判定读的是<b>终局助手消息本身</b>（pi {@code _checkCompaction} 读
     * {@code assistantMessage} 的 stopReason/errorMessage/usage/timestamp，
     * {@code agent-session.ts:2154}），旁路字段删除。</p>
     */
    private void endRequest(LaneState lane, Message.AssistantMessage assistant, long durationMs) {
        String stop = assistant.stopReason();
        if (llmSpan != null) {
            llmSpan.addAttribute("inputTokens", inputTokens);
            llmSpan.addAttribute("outputTokens", outputTokens);
            if (stop != null) {
                llmSpan.addAttribute("stopReason", stop);
            }
            llmSpan.close();
            ctx.telemetry().popCurrent(llmSpan);
            llmSpan = null;
        }
        ctx.telemetry().incrementCounter("llm.requests", 1);
        ctx.telemetry().recordTiming("llm.request.duration", durationMs);
        if (inputTokens > 0) {
            ctx.telemetry().incrementCounter("llm.tokens.input", inputTokens);
        }
        if (outputTokens > 0) {
            ctx.telemetry().incrementCounter("llm.tokens.output", outputTokens);
        }

        var usage = new StreamEvent.UsageInfo(inputTokens, outputTokens, lane.partial);
        ctx.hookSystem().fireAfterResponse(laneName,
            new ResponseContext(laneName, lane.runId, lane.partial, usage));
    }

    /**
     * 工具调用开始：记起算时刻与批次位置，并打开 {@code tool.execute} 跨度
     * （嵌套在 {@code harness.run} 之下，与旧路径的跨度形状一致）。
     */
    void noteToolStart(PiLoop.ToolCall call) {
        var callId = call.toolCallId();
        toolStartNanos.put(callId, System.nanoTime());
        batchCallIds.add(callId);
        var parent = laneRunSpanOrRoot();
        toolSpans.put(callId, parent.openSpan(new SpanOptions("tool.execute", Map.of(
            "toolCallId", callId,
            "toolName", call.toolName(),
            "toolIndex", batchCallIds.size() - 1,
            "argsChars", safeArgsChars(call.args())))));
    }

    /** 跨度的父级：本次运行的 {@code harness.run} 跨度，缺席时退回遥测根。 */
    private com.pijava.telemetry.TelemetryContext laneRunSpanOrRoot() {
        var lane = ctx.requireLane(laneName);
        return lane.runSpan != null ? lane.runSpan : ctx.telemetry();
    }

    /** 由 {@link PiToolRunner} 回填 {@code terminate}（事件载荷里没有这个字段）。 */
    void noteToolTerminate(String toolCallId, boolean terminate) {
        toolTerminate.put(toolCallId, terminate);
    }

    /** 由 {@link PiToolRunner} 回填 {@code before_tool} 是否放行。 */
    void noteToolAllowed(String toolCallId, boolean allowed) {
        toolAllowed.put(toolCallId, allowed);
    }

    /** 工具参数的字节数（可观测性只记规模，不记参数本身）。 */
    private static int safeArgsChars(Map<String, Object> args) {
        try {
            return ARGS_MAPPER.writeValueAsBytes(args).length;
        } catch (Exception e) {
            return args.toString().length();
        }
    }

    /**
     * 原始帧旁路：维护 {@code lane.partial} 与 token 记账。
     *
     * <p>两点都只能在这里做：① pi-java 把用量做成独立的
     * {@link StreamEvent.UsageInfo} 帧，而它不属于生命周期事件，{@link PiLoop} 不会为它发
     * {@code message_update}；② {@code lane.partial} 必须是**流式 partial**
     * （{@code com.pijava.ai.message.AssistantMessage}），与消息记录
     * （{@link Message.AssistantMessage}）是两个类型，只有原始帧才携带前者。
     * 旧路径 {@code AssistantStreamExecutor:114-121} 读的正是同一对字段。</p>
     */
    void noteStreamEvent(StreamEvent event) {
        if (event.partial() != null) {
            ctx.requireLane(laneName).partial = event.partial();
        }
        if (event instanceof StreamEvent.Start) {
            inputTokens = 0;
            outputTokens = 0;
        } else if (event instanceof StreamEvent.UsageInfo usage) {
            inputTokens += usage.inputTokens();
            outputTokens += usage.outputTokens();
        }
    }

    @Override
    public void emit(PiLoop.Event event) {
        if (event instanceof PiLoop.Event.MessageStart start) {
            onMessageStart(start.message());
        }
        if (event instanceof PiLoop.Event.MessageEnd end) {
            onMessageEnd(end.message());
        }
        if (downstream != null) {
            downstream.emit(event);
        }
    }

    /**
     * 新用户消息进上下文 ⇒ 溢出恢复闩锁复位（pi {@code message_start} 的 user 分支，
     * {@code agent-session.ts:643}；package 3c）。一次「用户回合」给一次新的恢复预算。
     */
    private void onMessageStart(Message message) {
        if (message instanceof Message.UserMessage) {
            ctx.requireLane(laneName).overflowRecoveryAttempted = false;
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 消息生命周期
    // ═══════════════════════════════════════════════════════════

    private void onMessageEnd(Message message) {
        var lane = ctx.requireLane(laneName);
        // 工作副本按 pi 的 processEvents 追加（agent.ts:554-557：message_end ⇒
        // state.messages.push）。**先于** alreadyPresent 的抑制 —— 起手的用户 prompt 因
        // 「日志里已有」不重复落盘，但照样要进副本，pi 的用户消息也是经 message_end 进
        // state.messages 的（docs/31 §4.2）。
        lane.messages.add(message);
        if (alreadyPresent.contains(message)) {
            return;
        }
        switch (message) {
            case Message.AssistantMessage assistant -> {
                // 事件跟踪的「本次运行最后见到的助手消息」（pi
                // {@code _lastAssistantMessage}，{@code agent-session.ts:636,691}）——
                // post-run 压缩检查看的是它，**不是**对工作副本的扫描：一轮没发过
                // 助手消息的运行收口时为 null（副本里可能躺着上一轮的）。
                this.lastAssistant = assistant;
                // 助手消息落定 ⇒ 本轮的批次结束（pi 的顺序是 message_end → 全部 start → 各自 end）。
                batchCallIds.clear();
                // 成功收尾（非 error/非 length）⇒ 溢出恢复闩锁复位
                // （pi {@code agent-session.ts:694-696}；package 3c）。
                String stopReason = assistant.stopReason();
                if (!"error".equals(stopReason) && !"length".equals(stopReason)) {
                    lane.overflowRecoveryAttempted = false;
                }
                // 循环可能把被中断的一轮改写成 aborted（PiLoopRunner.markAborted）。
                // lane.partial 必须跟着走：determineOutcome 与 lastAssistantMessage 都读它，
                // 不同步的话 abort 会被记成 completed。
                if (lane.partial != null) {
                    lane.partial = lane.partial.withStopReason(assistant.stopReason());
                }
                var durationMs = llmDurationMs();
                var entry = append(lane, message);
                emitAssistantRecords(lane, entry, assistant, durationMs);
                endRequest(lane, assistant, durationMs);
                lane.newestOwn = HarnessUtils.deriveNewestOwn(lane);
            }
            case Message.ToolResultMessage result -> emitToolRecords(lane, append(lane, message), result);
            default -> append(lane, message);
        }
        // 状态发布：旧路径在 AgentHarness 的每个 action 后发布，
        // 新驱动的等价点是「每条 entry 产生后」。少了这一步，会话快照会停在
        // 起手时的 token 数（AgentSessionToolIntegrationTest 抓的就是这个）。
        ctx.publishState(laneName);
    }

    /** 本次 {@code llm.request} 已经过的毫秒数。 */
    private long llmDurationMs() {
        return (System.nanoTime() - llmStartNanos) / 1_000_000;
    }

    /** 建 entry 并挂上车道。entry 一旦产生就直接进 transcript，没有中间态。 */
    private Entry.Message append(LaneState lane, Message message) {
        var entry = new Entry.Message(
            UUID.randomUUID().toString(), 0,
            lane.lastEntry() != null ? lane.lastEntry().id() : null, null, message, null);
        lane.transcript.add(entry);
        // 运行中写入 ⇒ 记为 deferred（docs/22 D3）。用户 prompt 由
        // RunLifecycle.startRun() 在起手时写入，不走这里，语义不受影响。
        HarnessUtils.recordDeferredWrite(lane, entry);
        return entry;
    }

    // ═══════════════════════════════════════════════════════════
    // 记录日志（旁路审计，docs/28 选项 C）
    // ═══════════════════════════════════════════════════════════

    /** 助手步的 {@code StepAttempt} + {@code UsageRecord}。 */
    private void emitAssistantRecords(LaneState lane, Entry.Message entry,
                                      Message.AssistantMessage assistant, long durationMs) {
        int attempt = stepIndex++;
        lane.records.add(new LaneRecord.StepAttempt(
            UUID.randomUUID().toString(), 0, laneName, null, lane.runId,
            StepKind.ASSISTANT, attempt, entry.id(), null,
            RunSpanFactory.modelLabel(ctx.model().get()), assembledMessageCount,
            toolCount(), RunSpanFactory.thinkingLabel(ctx.thinkingLevel().get()), durationMs));
        // 无条件发射（docs/21）：零 token 的一轮（error / abort）恰恰是折叠需要 stopReason
        // 的那种情形，按 tokens>0 设门槛会把它丢掉。
        lane.records.add(new LaneRecord.UsageRecord(
            UUID.randomUUID().toString(), 0, laneName, null,
            Usage.of(inputTokens, outputTokens), UsageCause.ASSISTANT, lane.runId,
            entry.id(), null, attempt, assistant.stopReason()));
        ctx.addTokens(inputTokens + outputTokens);
    }

    /**
     * 工具步的 {@code ToolStarted} / {@code ToolFinished}。
     *
     * <p>在**结果消息的 {@code message_end}** 上发射，而不是 {@code tool_execution_end}
     * —— pi 的发射顺序是 start → end → 结果消息（{@code agent-loop.ts:442-479}），
     * 到 end 时结果 entry 还不存在。</p>
     *
     * <p><b>已知缺口</b>：{@code effectiveArgs} 记空表 —— 参数已由
     * {@link PiToolRunner} 交给注册表，此处不再持有副本。
     * {@code RunSummaryAggregator} 只读 {@code isError}/{@code runId}，不受影响
     * （docs/28 §5.1 已记录该降级）。</p>
     */
    private void emitToolRecords(LaneState lane, Entry.Message entry,
                                 Message.ToolResultMessage result) {
        String callId = result.toolUseId();
        long startNanos = toolStartNanos.getOrDefault(callId, System.nanoTime());
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
        boolean terminate = Boolean.TRUE.equals(toolTerminate.get(callId));
        lane.records.add(new LaneRecord.ToolStarted(
            UUID.randomUUID().toString(), 0, laneName, null, lane.runId,
            "", 0, callId, result.toolName(), Map.of(), entry.id(), ReplayKind.NEVER));
        lane.records.add(new LaneRecord.ToolFinished(
            UUID.randomUUID().toString(), 0, laneName, null, lane.runId,
            callId, result.toolName(), result.isError(), terminate, entry.id(), durationMs));
        closeToolSpan(callId, result, terminate, durationMs);
        ctx.telemetry().incrementCounter("tool.executions", 1);
        if (result.isError()) {
            ctx.telemetry().incrementCounter("tool.errors", 1);
        }
        ctx.telemetry().recordTiming("tool.execute.duration", durationMs);
        toolStartNanos.remove(callId);
        toolTerminate.remove(callId);
    }

    /**
     * 关闭该调用的 {@code tool.execute} 跨度并补齐属性。
     *
     * <p>{@code batchSize} 只能在**收尾时**写：pi 保证一批的全部 start 早于任何 end
     * （{@code docs/29 §4.1}），所以到收尾时同一批的成员已经全部登记。</p>
     *
     * <p>没有跨度的调用（被 {@code PiLoopTools.failTruncated} 直接失败掉的截断调用
     * ——它**没有经过**工具端口）只是没有可观测性记录，不补一个假的。</p>
     */
    private void closeToolSpan(String callId, Message.ToolResultMessage result,
                               boolean terminate, long durationMs) {
        var span = toolSpans.remove(callId);
        var allowed = toolAllowed.remove(callId);
        if (span == null) {
            return;
        }
        span.addAttribute("batchSize", batchCallIds.size());
        // 缺席即放行：只有 PiToolRunner 的拒绝分支才回填 false。截断失败掉的调用没有跨度，
        // 走不到这里。
        span.addAttribute("allowed", allowed == null || allowed);
        span.addAttribute("isError", result.isError());
        span.addAttribute("terminate", terminate);
        span.addAttribute("durationMs", durationMs);
        span.close();
    }

    private int toolCount() {
        return ctx.toolRegistry() == null ? 0 : ctx.toolRegistry().toToolDefinitions().size();
    }
}
