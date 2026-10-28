package com.pijava.agent.harness;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.hook.BeforeSettleContext;
import com.pijava.agent.record.LaneRecord;
import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ToolRegistry;
import com.pijava.ai.message.Message;

/**
 * 用 {@link PiLoop} 驱动一次车道运行的引擎（{@code 原 docs/28 §5} 第 2 步）。
 *
 * <p><b>为什么不是 {@code coding-agent} 里的桥接器</b>：{@code 原 docs/28 §5.1} 假定
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
 * 它是「再起一次运行」，由会话层的驱动循环决定，见 {@code 原 docs/28 §5.1}。</p>
 */
public final class PiLaneEngine {

    private final ExecutionContext ctx;
    private final RunLifecycle lifecycle;
    private final ContextAssembler assembler;
    private final CompactionExecutor compactions;
    private final PostRunCompactionCheck postRun;
    /** 配置/请求钩簇＋队列簇（结构债拆分，纯移动）—— 本体在 {@link PiLaneConfig}。 */
    private final PiLaneConfig laneConfig;

    PiLaneEngine(ExecutionContext ctx, RunLifecycle lifecycle) {
        this.ctx = ctx;
        this.lifecycle = lifecycle;
        this.assembler = new ContextAssembler(ctx);
        this.compactions = new CompactionExecutor(ctx);
        this.laneConfig = new PiLaneConfig(ctx, assembler, compactions, this);
        this.postRun = new PostRunCompactionCheck(ctx, compactions);
    }

    // ═══════════════════════════════════════════════════════════
    // 入口
    // ═══════════════════════════════════════════════════════════

    /**
     * 一次运行的结果。
     *
     * @param runId      首个 pass 的 run id（= {@code OperationStarted} 的 id）。**必须在收口前取回**：
     *                   收口会关掉操作，之后 {@code snapshot().operation()} 为 null，
     *                   而 run summary 要靠它把记录过滤到本次驱动。
     * @param passRunIds 本次驱动实际跑过的全部 pass 的 run id（首元素 = {@code runId}）。
     *                   3c 起 post-run 压缩可以续跑（pi {@code _runAgentPrompt} 的
     *                   {@code while(await _handlePostAgentRun()) await agent.continue()}），
     *                   一次驱动不再恰好等于一次运行；记录按 id 集合过滤即覆盖全部 pass。
     * @param transcript 运行结束时的车道 transcript 快照
     */
    public record RunOutcome(String runId, List<String> passRunIds, List<Entry> transcript) {

        /** 单 pass 的旧形状（3c 之前的全部调用点）。 */
        public RunOutcome(String runId, List<Entry> transcript) {
            this(runId, List.of(runId), transcript);
        }
    }

    /**
     * 用一个新 prompt 驱动一次运行（pi {@code runAgentLoop}）。
     *
     * @param downstream 会话层接收器，可为 {@code null}
     */
    public RunOutcome run(String laneName, String prompt,
                          List<PromptImage> images, PiLoop.Sink downstream) {
        return run(laneName, prompt, images, List.of(), downstream);
    }

    /**
     * 批 26（docs/37 T2）：带 nextTurn custom 消息的 prompt 运行——pi
     * {@code agent-session.ts:2088-2092} 对位：custom 投影排在用户消息
     * <b>之后</b>进待发列表（{@code messages.push(user)} 后逐个 push），循环为每条
     * 发 message_start/end，sink 凭 {@code customOrigins} 把 custom 投影落成
     * {@code Entry.CustomMessage}（用户 entry 仍由 startRun 起手写入）。
     */
    public RunOutcome run(String laneName, String prompt, List<PromptImage> images,
                          List<LaneInfo.QueuedItem.Custom> customs, PiLoop.Sink downstream) {
        var lane = ctx.requireLane(laneName);
        // 起手前先判空闲，避免起手失败时留下一次多记的 turn 计数。
        if (lane.isRunning()) {
            throw new IllegalStateException("Cannot start run: lane " + laneName + " is not idle");
        }
        // 「新起一次运行」的计数点 —— 全仓唯一，两个入口（prompt / continueRun）里只有
        // 前者计数。旧路径由 AgentHarness.run 记，与新驱动分工一致。
        ctx.telemetry().incrementCounter("harness.turn", 1);
        // pi prompt() 起手前的同一压缩判定（agent-session.ts:1258-1263，3c）：扫副本找
        // 最后的助手消息（**含 aborted** ⇒ skipAbortedCheck=false，注释原文「catches
        // aborted responses」）。返回值忽略 —— 用户的新 prompt 马上就要发，不在这里
        // continue；压缩本身照跑。位置在 startRun 之前：pi 判定时用户 entry 尚未进
        // state.messages。
        postRun.checkBeforePrompt(laneName, lane);
        // ⚠️ 段补丁在 `startRun` 之前算：`startRun` 会把它**落盘**（排在任何用户 entry 之前，
        // 与 pi 的 `messages.unshift(updateMessage)` 同序 —— pi 的补丁与用户消息都经
        // `message_end` 落盘，java 现在同路，见 `RunLifecycle.startRun` 的 javadoc）。
        // 代价：`before_run` 钩子在两者之间触发；java 的 `RunContext` 是只读的，钩子契约上
        // 没有改工具/提示的入口（pi 对应的是可变的 `event.systemPromptOptions`），
        // 故此处不会读到陈旧输入。
        var promptMessage = HarnessUtils.buildUserMessage(prompt, images == null ? List.of() : images);
        var firstLoadout = assembler.promptLoadout(lane, lane.messages);
        var run = lifecycle.startRun(laneName, promptMessage, firstLoadout);
        // 起手把用户 entry 交给 sink 在 `message_end` 上写；这里直接拿**同一个对象**当 PiLoop
        // 的 prompt，sink 按引用抑制重复写入（PiLoop 会为 prompt 发 message_start/end）。
        var initial = new java.util.ArrayList<Message>();
        initial.add(promptMessage);
        for (var custom : customs) {
            var projection = HarnessUtils.buildQueuedCustomMessage(custom);
            lane.customOrigins.put(projection, custom);
            initial.add(projection);
        }
        return drive(laneName, run, List.copyOf(initial), downstream, firstLoadout);
    }

    /**
     * 从 transcript 尾部续跑（pi {@code runAgentLoopContinue}，自动重试用）。
     *
     * @param downstream 会话层接收器，可为 {@code null}
     */
    public RunOutcome continueRun(String laneName, PiLoop.Sink downstream) {
        var lane = ctx.requireLane(laneName);
        var prompts = continuePrompts(laneName, lane);
        var run = lifecycle.startContinue(laneName, prompts);
        return drive(laneName, run, prompts, downstream);
    }

    /**
     * pi {@code Agent.continue} 的前奏（{@code agent.ts:362-388}）：判据看的是
     * **工作副本尾部**（≙ {@code _state.messages}，不是日志 —— 日志可以停在一条
     * 已被副本摘掉的助手消息上，R2 删尾正是这种形状）。尾部是助手消息时：
     * 排空 steering ⇒ 以其消息起 prompt 模式的 pass（pi 的
     * {@code runPromptMessages(queued, {skipInitialSteeringPoll: true})} ——
     * 「drain 即摘除」让 skip 在 pi-java 天然成立）；steering 空 ⇒ 排空
     * followUp（pi 无 skip）；都空 ⇒ 抛。尾部非助手 ⇒ 空 prompt，
     * pi 的 {@code runContinuation()}。
     */
    List<Message> continuePrompts(String laneName, LaneState lane) {
        var messages = lane.messages;
        if (messages.isEmpty()) {
            throw new IllegalStateException("Cannot continue: no messages in context");
        }
        if (!(messages.get(messages.size() - 1) instanceof Message.AssistantMessage)) {
            return List.of();
        }
        var steer = laneConfig.drainSteer(laneName);
        if (!steer.isEmpty()) {
            return steer;
        }
        var followUps = laneConfig.drainFollowUp(laneName);
        if (!followUps.isEmpty()) {
            return followUps;
        }
        throw new IllegalStateException("Cannot continue from message role: assistant");
    }

    /**
     * pi {@code _willRetryAfterAgentEnd}（{@code agent-session.ts:721-733}）的公开
     * 入口（3d，{@code 原 docs/31 §8.22}）：宿主在转发 {@code agent_end} 前调用，用它
     * 装饰 {@code willRetry} 字段。「倒扫本次 agent_end 的 messages 找最后一条助手
     * 消息」是调用方的事（pi 扫的就是 event.messages）。计数读车道的会话级
     * {@code retryAttempt}（① 与装饰各算各的，与 pi 一致）。
     */
    public boolean retryWouldFollow(Message.AssistantMessage lastAssistant) {
        return postRun.retryWouldFollow(ctx.lane(), lastAssistant);
    }

    // ═══════════════════════════════════════════════════════════
    // 驱动
    // ═══════════════════════════════════════════════════════════

    private RunOutcome drive(String laneName, ActiveRun run, List<Message> prompts,
                             PiLoop.Sink downstream) {
        return drive(laneName, run, prompts, downstream, null);
    }

    /**
     * @param firstLoadout 起手段补丁（{@link #run} 已经把它写进日志与 pending 的那一个**同一对象**；
     *                     其余入口传 {@code null} ⇒ 由 {@link #startPass} 现算）
     */
    private RunOutcome drive(String laneName, ActiveRun run, List<Message> prompts,
                             PiLoop.Sink downstream, Message.SystemMessage firstLoadout) {
        var lane = ctx.requireLane(laneName);
        var runId = lane.runId;
        var passRunIds = new ArrayList<String>();
        passRunIds.add(runId);
        boolean settled = false;
        Message.SystemMessage pendingLoadout = firstLoadout;
        try {
            var passPrompts = prompts;
            // pi _runAgentPrompt:1109-1113 —— `while (await _handlePostAgentRun())
            // await agent.continue()`。post-run 检查（重试①在 3d、压缩②、队列③）说
            // 「还要跑」⇒ 收掉本 pass 的操作、再起一个 continue pass。Java 方言里每次
            // continue 是完整的一起一收（finishRun + startContinue，各带 runId），
            // pi 的 agent.continue() 不换 session 运行身份 —— 差异由 passRunIds 记账。
            while (true) {
                var pass = startPass(laneName, lane, passPrompts, downstream, pendingLoadout);
                pendingLoadout = null;
                try {
                    drivePass(pass);
                } catch (Throwable thrown) {
                    // pi `agent.ts:500-504`：runWithLifecycle 的 catch **无类型**
                    // （TS 的 catch 一律接得住），catch 体调 handleRunFailure ⇒ 把失败
                    // 压成一条助手消息 + 四事件（`agent.ts:506-525`）。Java 的
                    // Exception/Error 分裂是方言 —— 这里也要 Throwable，否则工具 worker
                    // 或钩子抛的 Error 会直穿出去（B5 的另一半见 `RunFailure` 的 javadoc）。
                    // **位置必须在 while 体内**：pi 的 `_handlePostAgentRun` 读的正是这条
                    // 合成消息（`agent-session.ts:1123`），放到循环外它就进不了重试判定。
                    RunFailure.settle(lane, pass.sink(), thrown);
                }
                if (!postRun.checkAfterRun(laneName, lane, pass.sink().lastAssistant())) {
                    // docs/15 裁决②：pi agent-session.ts:1839 —— ①②③ 都说收之后、
                    // finishRun 之前，before_settle 是 settle 前最后一道门。钩子全弃权
                    // ⇒ 收口（此刻队列必空，与 pi 无处理器时返回 hasQueuedMessages() 同值
                    // —— 无桥行为逐比特不变）。
                    Boolean settle = ctx.hookSystem().fireBeforeSettle(laneName,
                        new BeforeSettleContext(laneName, lane.runId));
                    if (!Boolean.TRUE.equals(settle)) {
                        break;
                    }
                }
                // pi 的 agent.continue() 在续跑前判「副本尾部 + 队列」（agent.ts:362-388），
                // 判崩了（助手尾且队列双空）异常要落在**上一个 pass 的开口之内** ——
                // finally 收一次即可；先 finishRun 再判会双收口（旧代码的隐患）。
                var nextPrompts = continuePrompts(laneName, lane);
                lifecycle.finishRun(laneName, HarnessUtils.determineOutcome(lane));
                lifecycle.startContinue(laneName, nextPrompts);
                passRunIds.add(lane.runId);
                passPrompts = nextPrompts;
            }
            lifecycle.finishRun(laneName, HarnessUtils.determineOutcome(lane));
            settled = true;
            return new RunOutcome(runId, List.copyOf(passRunIds), List.copyOf(lane.transcript));
        } finally {
            // 驱动**抛出**时同样要收口：否则 activeRun 永远挂着，车道再也起不了新运行，
            // waitForIdle 也会永远等下去。异常路径按 error 结算，原异常照常向上抛。
            if (!settled) {
                lifecycle.finishRun(laneName, "error");
            }
            run.done().complete(null);
        }
    }

    /**
     * 一个 pass 的**起手物**（pi 的「一个 {@code _runAgentPrompt} 调用」的静态部分）。
     *
     * <p>拆出来只为让 {@link #drive} 手里有 sink —— pass 抛出时要靠它把合成消息发进
     * 事件链（{@link RunFailure}，{@code 原 docs/31 §8.36.5}），而 sink 原本是
     * {@code runPass} 的局部变量。</p>
     */
    private record Pass(PiLaneSink sink, PiLoop.Config config, Context runContext,
                        List<Message> prompts) {}

    /**
     * 起手一个 pass：建 present 集合与 sink、装配配置、装系统提示、拷工作副本。
     *
     * <p>工作副本的一份**拷贝**：pi 的 {@code createContextSnapshot()} 交的就是
     * {@code this._state.messages.slice()}（{@code agent.ts:437-443}），循环往这份拷贝里推消息，
     * 车道的副本由 {@link PiLaneSink} 在 {@code message_end} 上跟进 —— 与 pi 的
     * {@code processEvents} 同形。</p>
     */
    private Pass startPass(String laneName, LaneState lane, List<Message> prompts,
                           PiLoop.Sink downstream) {
        return startPass(laneName, lane, prompts, downstream, null);
    }

    /**
     * @param preparedLoadout 已由 {@link #run} 算好并落盘的段补丁（**同一对象** ⇒
     *                        {@link PiLaneSink} 按引用抑制重复落盘）；{@code null} ⇒ 现算
     */
    private Pass startPass(String laneName, LaneState lane, List<Message> prompts,
                           PiLoop.Sink downstream, Message.SystemMessage preparedLoadout) {
        // 系统提示与工具在 run 起点装进 Context（pi 的 AgentContext）：
        // transformContext 只改消息，够不着这两样（pi 的钩子签名是 (messages) => messages）。
        // ⚠️ 这里的 `systemPrompt` 是**派生值**（渲染当前段表），不进任何行为判据 ——
        // pi 生产路径上 `Context.systemPrompt` 是死字段（`createContextSnapshot` 只交
        // messages+tools，`原 docs/52 §2 P12`），java 保留它是为了让请求录制与诊断看得见模型
        // 实际收到的提示。模型**看到**的提示走转录（段表 + 工具的合并声明）。
        var systemPrompt = assembler.renderPrompt(lane);
        var tools = activeTools(lane);

        // pi `_preparePromptAndToolLoadout` 的调用点之一（`agent-session.ts:1431-1432`）：
        // 起手算一次段补丁，**前置**进本 pass 的 pending。它随后被 `declareToolChanges` 当作
        // 锚点、把工具增删合并进同一条消息（`agent-loop.ts:311-315`）—— 于是本次请求只有
        // **一条**系统消息，同时带 sections 与 toolsAdded，与 pi 一致。
        var loadout = preparedLoadout == null ? assembler.promptLoadout(lane, lane.messages) : preparedLoadout;
        var passPrompts = loadout == null ? prompts : concatOne(loadout, prompts);

        // 起手时已在转录里的消息：PiLoop 会为它们重发 message_start/end 的，一律不再落盘。
        // （`preparedLoadout` 若已由 `run` 落盘，也在此列 —— 但它在 `declareToolChanges` 里会被
        // 复制一次（工具字段写回），引用对不上，故额外按**时间戳**做一次性抑制。）
        Set<Message> present = Collections.newSetFromMap(new IdentityHashMap<>());
        present.addAll(transcriptMessages(lane));
        var sink = new PiLaneSink(ctx, laneName, present, downstream);
        if (preparedLoadout != null) {
            sink.suppressSystemMessageAt(preparedLoadout.timestamp());
        }

        var config = laneConfig.configFor(laneName, lane, sink);
        var runContext = new Context(systemPrompt, new ArrayList<>(lane.messages), tools);
        return new Pass(sink, config, runContext, passPrompts);
    }

    /** 段补丁消息前置进本 pass 的 pending（pi 的 {@code messages.unshift(updateMessage)}）。 */
    private static List<Message> concatOne(Message head, List<Message> tail) {
        var both = new ArrayList<Message>(tail.size() + 1);
        both.add(head);
        both.addAll(tail);
        return both;
    }

    /**
     * 跑一个 pass = 一次 PiLoop 驱动（pi 的 {@code agent.prompt()} 或
     * {@code agent.continue()} 的一跑）。sink 每 pass 新建 ⇒ pi 的
     * {@code _lastAssistantMessage}「读后即清」在这里由 sink 的生命周期天然承担。
     */
    private static void drivePass(Pass pass) {
        if (pass.prompts().isEmpty()) {
            PiLoop.continueRun(pass.runContext(), pass.config(), pass.sink());
        } else {
            PiLoop.run(pass.prompts(), pass.runContext(), pass.config(), pass.sink());
        }
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
    List<AgentTool<?, ?>> activeTools(LaneState lane) {
        if (ctx.toolRegistry() == null) {
            return List.of();
        }
        var names = ctx.activeTools().get().stream()
            .map(AgentTool::name).collect(Collectors.toSet());
        return ctx.toolRegistry().activeOf(names);
    }

    private static List<Message> transcriptMessages(LaneState lane) {
        return lane.transcript.stream()
            .filter(Entry.Message.class::isInstance)
            .map(e -> ((Entry.Message) e).message())
            .collect(Collectors.toList());
    }

    /** 供测试断言：车道当前的记录日志。 */
    List<LaneRecord> recordsOf(String laneName) {
        return List.copyOf(ctx.requireLane(laneName).records);
    }
}
