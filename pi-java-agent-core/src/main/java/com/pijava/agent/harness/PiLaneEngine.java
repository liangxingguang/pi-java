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
        return drive(laneName, run, List.of(promptMessage), downstream);
    }

    /**
     * 从 transcript 尾部续跑（pi {@code runAgentLoopContinue}，自动重试用）。
     *
     * @param downstream 会话层接收器，可为 {@code null}
     */
    public RunOutcome continueRun(String laneName, PiLoop.Sink downstream) {
        var lane = ctx.requireLane(laneName);
        var run = lifecycle.startContinue(laneName);
        return drive(laneName, run, List.of(), downstream);
    }

    // ═══════════════════════════════════════════════════════════
    // 驱动
    // ═══════════════════════════════════════════════════════════

    private RunOutcome drive(String laneName, ActiveRun run, List<Message> prompts,
                             PiLoop.Sink downstream) {
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
            // 工作副本的一份**拷贝**：pi 的 createContextSnapshot() 交的就是
            // this._state.messages.slice()（agent.ts:437-443），循环往这份拷贝里推消息，
            // 车道的副本由 PiLaneSink 在 message_end 上跟进 —— 与 pi 的 processEvents 同形。
            var runContext = new Context(systemPrompt, new ArrayList<>(lane.messages), activeTools(lane));
            if (prompts.isEmpty()) {
                PiLoop.continueRun(runContext, config, sink);
            } else {
                PiLoop.run(prompts, runContext, config, sink);
            }

            // pi: 溢出检查在 agent_end **之后**（_handlePostAgentRun:1142 → _checkCompaction:2132），
            // 不在轮内。压缩改的是日志，工作副本随之重建，供下一次运行使用。
            sink.checkOverflowAfterRun(lane);
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
            ignored -> beforeRequest(laneName, lane, sink),
            next -> prepareNextTurn(laneName, lane),
            next -> fireShouldStopAfterTurn(laneName, lane),
            // 原始帧旁路：喂既有的 harness 广播链，会话层的记账代码因此无需改造。
            event -> {
                sink.noteStreamEvent(event);
                ctx.streamListener().get().accept(event);
            });
    }

    /**
     * pi {@code AgentLoopConfig.transformContext}：转成 provider 消息之前的最后一处改写。
     *
     * <p>此前这里 {@code assemble} 做三件事 ——应用暂存的配置变更、阈值自动压缩、从 entry
     * 日志重走 {@code pathToLeaf} 重建整份消息列表（{@code docs/31 §4.2}）。三件都已搬走：
     * 配置变更归 {@link #prepareNextTurn}（钩子返回点），压缩归 pi 的两处触发点，消息改由
     * 车道的工作副本承载。留在请求路径上的只有 {@code transform_context} 钩子，以及
     * 钩子看不到的两项**执行步开销**（{@code before_request} 与 {@code llm.request} 跨度）
     * —— 它们原本长在 {@code AssistantStreamExecutor} 里，属「执行步」，{@link PiLoop} 不带。</p>
     */
    private List<Message> beforeRequest(String laneName, LaneState lane, PiLaneSink sink) {
        var messages = assembler.transformContext(laneName, List.copyOf(lane.messages));
        sink.assembledMessageCount(messages.size());
        sink.beginRequest(lane, messages);
        return messages;
    }

    /**
     * pi {@code prepareNextTurn}（{@code agent-loop.ts:176-183}）。
     *
     * <p>钩子返回的配置变更**就地**落盘（{@code docs/31 §4.1}：字段赋值 + entry 同处），
     * 与 pi 的 {@code prepareNextTurnWithContext} 自己 {@code appendModelChange} 同形；
     * 交给循环的只是 {@code {model, reasoning}}。</p>
     *
     * <p>阈值压缩也在这里 —— pi 的 {@code _compactBeforeNextAssistantResponse} 就包在
     * {@code prepareNextTurnWithContext} 里（{@code agent-session.ts:542/557-577}）。压缩后
     * 从日志重建消息并**整体交回循环**（{@code NextTurnUpdate.context}），这正是 pi 那条
     * 「压缩靠 context 通道生效」的路径。</p>
     */
    private PiLoop.NextTurnUpdate prepareNextTurn(String laneName, LaneState lane) {
        var update = ctx.hookSystem().firePrepareNextTurn(laneName,
            new PrepareNextTurnContext(laneName, lane.runId, lane.partial, List.of()));
        if (update != null) {
            assembler.applyTurnUpdate(laneName, lane, update);
        }
        boolean compacted = compactions.checkThreshold(laneName, lane);
        return new PiLoop.NextTurnUpdate(
            update == null ? null : update.model(),
            update == null || update.thinkingLevel() == null ? null
                : ModelThinkingLevel.of(LaneState.parseThinkingLabel(update.thinkingLevel())),
            compacted ? rebuiltContext(lane) : null);
    }

    /** 压缩后的上下文整体替换（pi {@code {...snapshot.context, messages: state.messages.slice()}}）。 */
    private Context rebuiltContext(LaneState lane) {
        return new Context(assembler.buildSystemPrompt(lane),
            new ArrayList<>(lane.messages), activeTools(lane));
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

    /**
     * 生效的工具**本体**：注册表按生效的 {@code activeTools} 过滤。
     *
     * <p>交给 {@link Context} 的是 {@code AgentTool} 而非定义 —— 循环要读
     * {@code executionMode} 决定整批走顺序还是并行（pi {@code agent-loop.ts:417-421}）。
     * provider 只在请求边界拿到投影（{@code ToolRegistry.definitionsOf}）。</p>
     */
    private List<AgentTool<?, ?>> activeTools(LaneState lane) {
        if (ctx.toolRegistry() == null) {
            return List.of();
        }
        Set<String> names = ctx.activeTools().get().stream()
            .map(AgentTool::name).collect(Collectors.toSet());
        return ctx.toolRegistry().all().stream()
            .filter(t -> names.contains(t.name()))
            .toList();
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
