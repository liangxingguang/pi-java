package com.pijava.agent.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import com.pijava.agent.hook.PrepareNextTurnContext;
import com.pijava.agent.hook.ShouldStopAfterTurnContext;
import com.pijava.agent.record.LaneRecord;
import com.pijava.agent.record.QueueKind;
import com.pijava.ai.message.Message;
import com.pijava.ai.thinking.ModelThinkingLevel;

/**
 * {@link PiLaneEngine} 的**配置/请求钩簇＋队列簇**抽离（结构债拆分：行为零变化纯移动，
 * javadoc/注释逐字随代码搬）。引擎侧调用点改包私有委托；
 * {@code RunOutcome}/{@code run}×2/{@code continueRun}/{@code retryWouldFollow} 的
 * public API 不动。
 *
 * <p>{@code rebuiltContext} 的工具集读 {@link PiLaneEngine#activeTools}（包私有回呼），
 * 与移动前同源。</p>
 */
final class PiLaneConfig {

    private final ExecutionContext ctx;
    private final ContextAssembler assembler;
    private final CompactionExecutor compactions;
    private final PiLaneEngine engine;

    PiLaneConfig(ExecutionContext ctx, ContextAssembler assembler,
                 CompactionExecutor compactions, PiLaneEngine engine) {
        this.ctx = ctx;
        this.assembler = assembler;
        this.compactions = compactions;
        this.engine = engine;
    }

    // ═══════════════════════════════════════════════════════════
    // 配置
    // ═══════════════════════════════════════════════════════════

    PiLoop.Config configFor(String laneName, LaneState lane, PiLaneSink sink) {
        var signal = lane.abortSignal();
        // 批 16（T2 修真）：查找面接活跃集活视图（pi currentContext.tools 对位）——
        // setActiveTools 失活的工具下轮调用即 "not found"（pi 同断言的 oracle 行为）。
        var toolRunner = new PiToolRunner(laneName, ctx.toolRegistry(), ctx.hookSystem(),
            ctx.toolContext(), signal, sink.toolRecords()::noteToolAllowed, ctx.activeTools());
        return new PiLoop.Config(
            ctx.model().get(),
            ctx.thinkingLevel().get(),
            ctx.thinkingLevelMap(),
            ctx.toolExecution().get(),
            observing(toolRunner, sink),
            ctx.streamFn(),
            signal,
            () -> drainSteer(laneName),
            () -> drainFollowUp(laneName),
            ignored -> beforeRequest(laneName, lane, sink),
            // pi prepareRequest（agent-loop.ts:219-239）：每次 provider 请求前的改写机会。
            // ⚠️ 留痕（批 2 §2.5）：llm.request 跨度的 model/thinking 标签读车道 supplier
            // （ctx.model().get()），prepareRequest 的 run 内替换不反映到跨度标签 ——
            // 与 pi 的遥测口径未核，记窄差。
            // 批 27（B162）：pi :790-791——failedResponse 读取即消费（每次请求都清，
            // 不只虚拟选型；pi 的包装器在判 isVirtualModel 之前就清）。
            request -> {
                var failed = lane.failedResponse;
                lane.failedResponse = null;
                return ctx.hookSystem().firePrepareRequest(laneName,
                    new PiLoop.PrepareRequestContext(request.context(), request.model(),
                        request.thinking(), failed));
            },
            next -> prepareNextTurn(laneName, lane),
            // docs/15：修真 —— 旧接线丢掉 next，给钩子喂 lane.partial + 空工具结果；
            // pi 的 FinishTurn 拿的是定案消息与本轮真工具结果（types.ts:149-158）。
            next -> ctx.hookSystem().fireFinishTurn(laneName,
                new ShouldStopAfterTurnContext(laneName, lane.runId, next.message(),
                    next.toolResults())),
            // 原始帧旁路：喂既有的 harness 广播链，会话层的记账代码因此无需改造。
            event -> {
                sink.noteStreamEvent(event);
                ctx.streamListener().get().accept(event);
            },
            ctx.thinkingBudgets());
    }

    /**
     * 把两相端口包上 harness 侧的观测：{@code tool.execute} 跨度与批次位置在
     * {@code prepare} 打开（此时循环刚发完 {@code tool_execution_start}），
     * {@code terminate} 回填在结果定局时 —— immediate 分支（拒绝 / 未找到 / 参数非法 /
     * 中止）在准备时定局，其余在执行完成时。两条分支都回填，跨度才不会漏账。
     */
    private static PiLoop.ToolRunner observing(PiToolRunner runner, PiLaneSink sink) {
        return new PiLoop.ToolRunner() {
            @Override
            public PiLoop.Preparation prepare(PiLoop.ToolCall call) {
                sink.toolRecords().noteToolStart(call);
                var preparation = runner.prepare(call);
                if (preparation instanceof PiLoop.ImmediateOutcome immediate) {
                    sink.toolRecords().noteToolTerminate(call.toolCallId(), immediate.outcome().terminate());
                }
                return preparation;
            }

            @Override
            public PiLoop.ToolOutcome execute(PiLoop.Prepared prepared, PiLoop.Sink emit) {
                var outcome = runner.execute(prepared, emit);
                sink.toolRecords().noteToolTerminate(prepared.call().toolCallId(), outcome.terminate());
                return outcome;
            }
        };
    }

    /**
     * pi {@code AgentLoopConfig.transformContext}：转成 provider 消息之前的最后一处改写。
     *
     * <p>此前这里 {@code assemble} 做三件事 ——应用暂存的配置变更、阈值自动压缩、从 entry
     * 日志重走 {@code pathToLeaf} 重建整份消息列表（{@code 原 docs/31 §4.2}）。三件都已搬走：
     * 配置变更归 {@link #prepareNextTurn}（钩子返回点），压缩归 pi 的两处触发点，消息改由
     * 车道的工作副本承载。留在请求路径上的只有 {@code transform_context} 钩子，以及
     * 钩子看不到的**执行步开销**（{@code llm.request} 跨度）—— 它原本长在
     * {@code AssistantStreamExecutor} 里，属「执行步」，{@link PiLoop} 不带。
     * （旧的 {@code before_request} void 钩子已随 #34 退役：pi 的 {@code prepareRequest}
     * 是可改写的运行时钩子，走 {@code Config.prepareRequest} 槽。）</p>
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
     * <p>钩子返回的配置变更**就地**落盘（{@code 原 docs/31 §4.1}：字段赋值 + entry 同处），
     * 与 pi 的 {@code prepareNextTurnWithContext} 自己 {@code appendModelChange} 同形；
     * 交给循环的只是 {@code {model, reasoning}}。</p>
     *
     * <p>阈值压缩也在这里 —— pi 的 {@code _compactBeforeNextAssistantResponse} 就包在
     * {@code prepareNextTurnWithContext} 里（{@code agent-session.ts:542/557-577}）。压缩后
     * 从日志重建消息并**整体交回循环**（{@code NextTurnUpdate.context}），这正是 pi 那条
     * 「压缩靠 context 通道生效」的路径。</p>
     *
     * <p>段补丁的**第二个调用点**（pi {@code agent-session.ts:599-611}）：pi 在同一个钩子里
     * 先压缩、再按 {@code nextContext.messages}（＝ 压缩后的列表）算差分，把补丁**追加**进
     * {@code messages} 交回循环。补丁随后进 pending，被 {@code declareToolChanges} 当锚点并入
     * 工具字段 —— 于是「中途改提示」与「中途改工具集」落在**同一条**系统消息上。</p>
     */
    private PiLoop.NextTurnUpdate prepareNextTurn(String laneName, LaneState lane) {
        var update = ctx.hookSystem().firePrepareNextTurn(laneName,
            new PrepareNextTurnContext(laneName, lane.runId, lane.partial, List.of()));
        if (update != null) {
            assembler.applyTurnUpdate(laneName, lane, update);
        }
        boolean compacted = compactions.checkThreshold(laneName, lane);
        // 差分基准与被交回循环的列表**同源**（pi `_preparePromptAndToolLoadout(options,
        // nextContext.messages)`）：压缩过就用重建后的那份，否则用工作副本。
        var messages = compacted ? List.copyOf(lane.messages) : lane.messages;
        var loadout = assembler.promptLoadout(lane, messages);
        return new PiLoop.NextTurnUpdate(
            update == null ? null : update.model(),
            update == null || update.thinkingLevel() == null ? null
                : ModelThinkingLevel.of(LaneState.parseThinkingLabel(update.thinkingLevel())),
            // ⚠️ **无条件**交回 context，与 pi 同形：`prepareNextTurnWithContext` 的返回值里
            // `context: {...nextContext, tools: state.tools.slice()}` 是**每次都重建**的
            // （`agent-session.ts:605-608`），于是**运行中**的 `setActiveTools`／改提示会随这条
            // 通道刷进循环的 `AgentContext`。此前 java 只在压缩时交回 ⇒ `context.tools` 停在
            // run 起手那一刻的快照，`declareToolChanges` 拿它当「可执行集」比较 ⇒ 同一 run 内的
            // 工具变更**不会**产生 `toolsAdded`/`toolsRemoved`（包 A4c 的探针 C4 撞出来的）。
            rebuiltContext(lane),
            loadout == null ? null : List.of(loadout));
    }

    /** 压缩后的上下文整体替换（pi {@code {...snapshot.context, messages: state.messages.slice()}}）。 */
    private Context rebuiltContext(LaneState lane) {
        return new Context(assembler.renderPrompt(lane),
            new ArrayList<>(lane.messages), engine.activeTools(lane));
    }

    // ═══════════════════════════════════════════════════════════
    // 队列
    // ═══════════════════════════════════════════════════════════

    /**
     * steer 队列 → 下一轮前的注入消息。
     *
     * <p>消费点即发射点：旧路径在 {@code executeConsumeQueueItem} 与
     * {@code injectUserMessages} 两处发 {@code QueueConsumed}（原 docs/21 D10），
     * 这里归一为「谁 drain 谁发射」。消息本身由 PiLoop 发
     * {@code message_start}/{@code message_end}，再由 {@link PiLaneSink} 落成 entry。</p>
     */
    List<Message> drainSteer(String laneName) {
        var items = ctx.queueManager().drainSteer(laneName);
        if (items.isEmpty()) {
            return List.of();
        }
        emitQueueConsumed(laneName, QueueKind.STEER, items);
        return toMessages(laneName, items);
    }

    /** follow-up 队列 → 内层循环耗尽后的下一轮。 */
    List<Message> drainFollowUp(String laneName) {
        var items = ctx.queueManager().drainFollowUp(laneName);
        if (items.isEmpty()) {
            return List.of();
        }
        emitQueueConsumed(laneName, QueueKind.FOLLOW_UP, items);
        return toMessages(laneName, items);
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
     *
     * <p>批 26（docs/37 T1，裁决⑧）：custom 项经
     * {@link HarnessUtils#buildQueuedCustomMessage} 投影为 user 消息，并把
     * 「消息实例 ⇒ custom 载荷」登记到 {@code lane.customOrigins}——
     * {@link PiLaneSink} 的 message_end 落盘臂凭它落成
     * {@code Entry.CustomMessage}（pi agent-session.ts:1147-1155 对位）。</p>
     */
    private List<Message> toMessages(String laneName, List<LaneInfo.QueuedItem> items) {
        var lane = ctx.requireLane(laneName);
        return items.stream()
            .map(item -> switch (item) {
                case LaneInfo.QueuedItem.Text text -> HarnessUtils.buildUserMessage(
                    text.prompt(), text.images());
                case LaneInfo.QueuedItem.Custom custom -> {
                    var message = HarnessUtils.buildQueuedCustomMessage(custom);
                    lane.customOrigins.put(message, custom);
                    yield message;
                }
            })
            .collect(Collectors.toList());
    }
}
