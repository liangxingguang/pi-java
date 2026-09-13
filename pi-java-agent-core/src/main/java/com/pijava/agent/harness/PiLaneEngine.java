package com.pijava.agent.harness;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.hook.PrepareNextTurnContext;
import com.pijava.agent.hook.ShouldStopAfterTurnContext;
import com.pijava.agent.record.LaneRecord;
import com.pijava.agent.record.QueueKind;
import com.pijava.agent.tool.AgentTool;
import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.message.Message;
import com.pijava.ai.thinking.ModelThinkingLevel;

/**
 * 用 {@link PiLoop} 驱动一次车道运行的引擎（{@code docs/28 §5} 第 2 步）。
 *
 * <p><b>为什么不是 {@code coding-agent} 里的桥接器</b>：{@code docs/28 §5.1} 假定
 * 「桥接器 = 事件转发 + {@code appendEntry}」。实测不成立 —— 车道起手、上下文装配、
 * 自动压缩、记录日志都绑定 {@link LaneState}（包内可见），会话层拿不到。因此引擎留在这里，
 * 会话层只经由 {@link PiLoop.Sink} 收事件。</p>
 *
 * <p><b>复用什么</b>：车道起手直接调 {@link RunLifecycle#startRun}，终局收口调
 * {@link RunLifecycle#finishRun} —— 起手的 {@code runId}／{@code ActiveRun}／
 * {@code before_run} 钩子／用户 entry／{@code OperationStarted} 记录，以及收口的
 * {@code OperationFinished}／{@code before_run_end}／run span，都与驱动循环无关，
 * 重写只会引入漂移。</p>
 *
 * <p><b>只替换中间那段</b>：旧路径的 {@code peekAction → executeAction} 步进链换成
 * pi 的 {@code runLoop}。工具执行经 {@link PiToolRunner}，上下文装配经
 * {@link ContextAssembler}，自动压缩经 {@link CompactionExecutor}。</p>
 *
 * <p><b>与 pi 的有意差异（2b-1）</b>：{@code nextRun} 队列不在这里消费 ——
 * 它是「再起一次运行」，由会话层的驱动循环决定，见 {@code docs/28 §5.1}。</p>
 */
public final class PiLaneEngine {

    private final ExecutionContext ctx;
    private final RunLifecycle lifecycle;
    private final ContextAssembler assembler;
    private final CompactionExecutor compactions;

    PiLaneEngine(ExecutionContext ctx, RunLifecycle lifecycle) {
        this.ctx = ctx;
        this.lifecycle = lifecycle;
        this.assembler = new ContextAssembler(ctx);
        this.compactions = new CompactionExecutor(ctx);
    }

    // ═══════════════════════════════════════════════════════════
    // 入口
    // ═══════════════════════════════════════════════════════════

    /**
     * 一次运行的结果。
     *
     * @param runId      本次运行的 id（= {@code OperationStarted} 的 id）。**必须在收口前取回**：
     *                   收口会关掉操作，之后 {@code snapshot().operation()} 为 null，
     *                   而 run summary 要靠它把记录过滤到本次驱动。
     * @param transcript 运行结束时的车道 transcript 快照
     */
    public record RunOutcome(String runId, List<Entry> transcript) {}

    /**
     * 用一个新 prompt 驱动一次运行（pi {@code runAgentLoop}）。
     *
     * @param downstream 会话层接收器，可为 {@code null}
     */
    public RunOutcome run(String laneName, String prompt,
                          List<PromptImage> images, PiLoop.Sink downstream) {
        var lane = ctx.requireLane(laneName);
        // 起手前先判空闲，避免起手失败时留下一次多记的 turn 计数。
        if (lane.isRunning()) {
            throw new IllegalStateException("Cannot start run: lane " + laneName + " is not idle");
        }
        // 「新起一次运行」的计数点 —— 全仓唯一，两个入口（prompt / continueRun）里只有
        // 前者计数。旧路径由 AgentHarness.run 记，与新驱动分工一致。
        ctx.telemetry().incrementCounter("harness.turn", 1);
        var run = lifecycle.startRun(laneName, prompt, images == null ? List.of() : images);
        // 起手已把用户 entry 写进 transcript；取回**同一个对象**作为 PiLoop 的 prompt，
        // 这样 PiLaneSink 才能按引用抑制重复写入（PiLoop 会为 prompt 发 message_start/end）。
        var promptMessage = lastUserMessage(lane);
        if (promptMessage == null) {
            throw new IllegalStateException("run() did not append a user entry to lane " + laneName);
        }
        return drive(laneName, run, List.of(promptMessage), new ArrayList<>(), downstream);
    }

    /**
     * 从 transcript 尾部续跑（pi {@code runAgentLoopContinue}，自动重试用）。
     *
     * @param downstream 会话层接收器，可为 {@code null}
     */
    public RunOutcome continueRun(String laneName, PiLoop.Sink downstream) {
        var lane = ctx.requireLane(laneName);
        var run = lifecycle.startContinue(laneName);
        return drive(laneName, run, List.of(), transcriptMessages(lane), downstream);
    }

    // ═══════════════════════════════════════════════════════════
    // 驱动
    // ═══════════════════════════════════════════════════════════

    private RunOutcome drive(String laneName, ActiveRun run, List<Message> prompts,
                             List<Message> context, PiLoop.Sink downstream) {
        var lane = ctx.requireLane(laneName);
        var runId = lane.runId;
        boolean settled = false;
        try {
            // 起手时已在转录里的消息：PiLoop 会为它们重发 message_start/end 的，一律不再落盘。
            Set<Message> present = Collections.newSetFromMap(new IdentityHashMap<>());
            present.addAll(transcriptMessages(lane));
            var sink = new PiLaneSink(ctx, laneName, present, downstream);

            var config = configFor(laneName, lane, sink);
            // 系统提示与工具在 run 起点装进 Context（pi 的 AgentContext）：
            // transformContext 只改消息，够不着这两样（pi 的钩子签名是 (messages) => messages）。
            var systemPrompt = assembler.buildSystemPrompt(lane);
            sink.systemPrompt(systemPrompt);
            var runContext = new Context(systemPrompt, context, toolDefs(lane));
            if (prompts.isEmpty()) {
                PiLoop.continueRun(runContext, config, sink);
            } else {
                PiLoop.run(prompts, runContext, config, sink);
            }

            lifecycle.finishRun(laneName, HarnessUtils.determineOutcome(lane));
            settled = true;
            return new RunOutcome(runId, List.copyOf(lane.transcript));
        } finally {
            // 驱动**抛出**时同样要收口：否则 activeRun 永远挂着，车道再也起不了新运行，
            // waitForIdle 也会永远等下去。异常路径按 error 结算，原异常照常向上抛。
            if (!settled) {
                lifecycle.finishRun(laneName, "error");
            }
            run.done().complete(null);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 配置
    // ═══════════════════════════════════════════════════════════

    private PiLoop.Config configFor(String laneName, LaneState lane, PiLaneSink sink) {
        var signal = lane.abortSignal();
        var toolRunner = new PiToolRunner(laneName, ctx.toolRegistry(), ctx.hookSystem(),
            ctx.toolContext(), signal, sink::noteToolAllowed);
        return new PiLoop.Config(
            ctx.model().get(),
            ctx.thinkingLevel().get(),
            ctx.thinkingLevelMap(),
            ctx.toolExecution().get(),
            call -> {
                sink.noteToolStart(call);
                var outcome = toolRunner.run(call);
                sink.noteToolTerminate(call.toolCallId(), outcome.terminate());
                return outcome;
            },
            ctx.streamFn(),
            signal,
            () -> drainSteer(laneName),
            () -> drainFollowUp(laneName),
            ignored -> assemble(laneName, lane, sink),
            next -> prepareNextTurn(laneName, lane),
            next -> fireShouldStopAfterTurn(laneName, lane),
            // 原始帧旁路：喂既有的 harness 广播链，会话层的记账代码因此无需改造。
            event -> {
                sink.noteStreamEvent(event);
                ctx.streamListener().get().accept(event);
            });
    }

    /**
     * 每轮请求前的上下文装配 —— 对应旧路径 {@code AssistantStreamExecutor:60-64} 的三步。
     *
     * <p>{@code PiLoop} 每次调用都会把它的内部消息列表传进来，但**本引擎不使用它**：
     * pi-java 的真源是车道 transcript，装配必须从那里重建（含压缩摘要与车道级覆盖）。
     * 这也是 {@code docs/28} 所说的「车道是唯一真源」的落地方式。</p>
     *
     * <p><b>这里只产消息。</b> 系统提示与工具定义不在其中 —— 它们由
     * {@link #drive} 在 run 起点装进 {@link Context}，与 pi 的
     * {@code AgentContext} 一致（pi 的 {@code transformContext} 同样看不到它们）。</p>
     */
    private List<Message> assemble(String laneName, LaneState lane, PiLaneSink sink) {
        assembler.applyPendingTurnUpdate(laneName, lane);
        compactions.checkAutoCompact(laneName, lane);
        var messages = assembler.buildMessagesForLane(laneName, lane);
        sink.assembledMessageCount(messages.size());
        // before_request 钩子 + llm.request 跨度：原本长在 AssistantStreamExecutor 里，
        // 属「执行步」开销，PiLoop 不带，必须在这里补上。
        sink.beginRequest(lane, messages);
        return messages;
    }

    /**
     * pi 的 {@code prepareNextTurn}：钩子结果同时**回填车道**（由下一轮
     * {@link ContextAssembler#applyPendingTurnUpdate} 落成配置 entry 并更新 harness 状态）
     * 与**回给循环**（下一轮直接使用新的 model / thinking）。两侧在同一次请求前生效，不会打架。
     */
    private PiLoop.NextTurnUpdate prepareNextTurn(String laneName, LaneState lane) {
        var update = ctx.hookSystem().firePrepareNextTurn(laneName,
            new PrepareNextTurnContext(laneName, lane.runId, lane.partial, List.of()));
        if (update == null) {
            return null;
        }
        lane.pendingTurnUpdate = update;
        return new PiLoop.NextTurnUpdate(
            update.model(),
            update.thinkingLevel() == null ? null
                : ModelThinkingLevel.of(HarnessState.parseThinkingLabel(update.thinkingLevel())));
    }

    private boolean fireShouldStopAfterTurn(String laneName, LaneState lane) {
        return ctx.hookSystem().fireShouldStopAfterTurn(laneName,
            new ShouldStopAfterTurnContext(laneName, lane.runId, lane.partial, List.of()));
    }

    // ═══════════════════════════════════════════════════════════
    // 队列
    // ═══════════════════════════════════════════════════════════

    /**
     * steer 队列 → 下一轮前的注入消息。
     *
     * <p>消费点即发射点：旧路径在 {@code executeConsumeQueueItem} 与
     * {@code injectUserMessages} 两处发 {@code QueueConsumed}（docs/21 D10），
     * 这里归一为「谁 drain 谁发射」。消息本身由 PiLoop 发
     * {@code message_start}/{@code message_end}，再由 {@link PiLaneSink} 落成 entry。</p>
     */
    private List<Message> drainSteer(String laneName) {
        var items = ctx.queueManager().drainSteer(laneName);
        if (items.isEmpty()) {
            return List.of();
        }
        emitQueueConsumed(laneName, QueueKind.STEER, items);
        return toMessages(items);
    }

    /** follow-up 队列 → 内层循环耗尽后的下一轮。 */
    private List<Message> drainFollowUp(String laneName) {
        var items = ctx.queueManager().drainFollowUp(laneName);
        if (items.isEmpty()) {
            return List.of();
        }
        emitQueueConsumed(laneName, QueueKind.FOLLOW_UP, items);
        return toMessages(items);
    }

    private void emitQueueConsumed(String laneName, QueueKind kind,
                                   List<LaneInfo.QueuedItem> items) {
        var lane = ctx.requireLane(laneName);
        lane.records.add(new LaneRecord.QueueConsumed(
            UUID.randomUUID().toString(), 0, laneName, null, lane.runId, kind,
            items.stream().map(HarnessUtils::provisionedQueueTarget).toList()));
    }

    /**
     * 取走的队列项 → 注入的用户消息，**一项一条**。
     *
     * <p>pi 的 {@code PendingMessageQueue.drain()} 在 {@code "all"} 模式下原样返回全部
     * 消息（{@code agent.ts:143-148}），循环再逐条 {@code push} 并各自发
     * {@code message_start}/{@code message_end}（{@code agent-loop.ts:200-208}）
     * —— **没有合并**。旧的 {@code ActionExecutor} 把整批拼成一条带空行分隔的用户消息，
     * 那是 pi-java 自己的构造，本次对齐予以纠正。</p>
     */
    private static List<Message> toMessages(List<LaneInfo.QueuedItem> items) {
        return items.stream()
            .map(i -> HarnessUtils.buildUserMessage(i.prompt(), i.images()))
            .collect(Collectors.toList());
    }

    // ═══════════════════════════════════════════════════════════
    // 工具与消息视图
    // ═══════════════════════════════════════════════════════════

    /** 生效的工具定义：注册表按车道级 {@code activeTools} 过滤（对齐 AssistantStreamExecutor:72-78）。 */
    private List<ToolDefinition> toolDefs(LaneState lane) {
        if (ctx.toolRegistry() == null) {
            return List.of();
        }
        var effective = lane.activeTools != null ? lane.activeTools : ctx.activeTools().get();
        Set<String> names = effective.stream().map(AgentTool::name).collect(Collectors.toSet());
        return ctx.toolRegistry().toToolDefinitions().stream()
            .filter(td -> names.contains(td.name()))
            .collect(Collectors.toList());
    }

    private static List<Message> transcriptMessages(LaneState lane) {
        return lane.transcript.stream()
            .filter(Entry.Message.class::isInstance)
            .map(e -> ((Entry.Message) e).message())
            .collect(Collectors.toList());
    }

    /** 起手写入的那条用户消息（{@code run()} 保证存在）。 */
    private static Message lastUserMessage(LaneState lane) {
        for (int i = lane.transcript.size() - 1; i >= 0; i--) {
            if (lane.transcript.get(i) instanceof Entry.Message m
                    && m.message() instanceof Message.UserMessage) {
                return m.message();
            }
        }
        return null;
    }

    /** 供测试断言：车道当前的记录日志。 */
    List<LaneRecord> recordsOf(String laneName) {
        return List.copyOf(ctx.requireLane(laneName).records);
    }
}
