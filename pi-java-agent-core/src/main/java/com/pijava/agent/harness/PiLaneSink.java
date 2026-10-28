package com.pijava.agent.harness;

import java.util.List;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.hook.MessageEndContext;
import com.pijava.agent.record.LaneRecord;
import com.pijava.agent.record.StepKind;
import com.pijava.agent.record.UsageCause;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.stream.StreamEvent;

/**
 * 把 {@link PiLoop} 的事件翻译成**车道状态**：创建 entry、写记录日志、维护
 * {@code lane.partial} 与 {@code lane.newestOwn}。
 *
 * <p><b>为什么在 agent-core 而不是 coding-agent</b>：这些操作全部落在
 * {@link LaneState} 上，而它是包内可见的。<b>这是 {@code 原 docs/28 §5.1} 的一处更正</b>
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
 * 结构统一到 {@code message_end} 一个点上 —— 这正是 {@code 原 docs/28} 选项 C 所要求的
 * 「等价性由结构保证」。</p>
 */
final class PiLaneSink implements PiLoop.Sink {

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
    /** 起手已落盘的段补丁的时间戳（一次性抑制；见 suppressSystemMessageAt）。 */
    private Instant suppressedSystemTimestamp;

    /** 事件串行化的监视器（{@code 原 docs/31 §8.23}）—— 见 {@link #emit}。 */
    private final Object emitLock = new Object();

    /** 工具簇（结构债拆分，纯移动）—— 批次表/跨度/判定回填与记录发射，本体在 {@link PiLaneToolRecords}。 */
    private final PiLaneToolRecords toolRecords;

    /** 本次请求实际发给 provider 的消息数，由引擎的 {@code transformContext} 回填。 */
    private int assembledMessageCount;

    /** 本次请求的 step 序号（每次助手流 +1）。 */
    private int stepIndex;

    /** 请求遥测族（llm.request 跨度＋token 旁路）—— #94 抽离，见 {@link PiLaneRequestTelemetry}。 */
    private final PiLaneRequestTelemetry requestTelemetry;

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
        this.requestTelemetry = new PiLaneRequestTelemetry(ctx, laneName);
        this.toolRecords = new PiLaneToolRecords(ctx, laneName);
    }

    /** 工具簇的直达口（引擎的观测包装与 {@code PiToolRunner} 回填走这里）。 */
    PiLaneToolRecords toolRecords() {
        return toolRecords;
    }

    /** 由引擎的 {@code transformContext} 回填，供 {@code StepAttempt.messageCount} 使用。 */
    void assembledMessageCount(int count) {
        this.assembledMessageCount = count;
    }

    /**
     * 由引擎在 run 起点回填：这条**段补丁**系统消息已在 {@code RunLifecycle.startRun} 里落盘，
     * 循环稍后发来的那一份是它的**副本**（{@code declareToolChanges} 把工具字段写回时复制），
     * 引用对不上 {@link #alreadyPresent}。故按**时间戳**做一次性抑制。
     *
     * <p>为什么不是按引用：锚点那一支的 {@code withToolChanges} 有条件才复制
     * （「无变化且自己也没声明过工具 ⇒ 原对象返回」），而补丁在起手时按定义**就是**变更，
     * 必然走复制那一支。时间戳来自 {@code Instant.now()}，逐条唯一，且只对本 pass 的第一条
     * 同时间戳的系统消息生效。</p>
     */
    void suppressSystemMessageAt(Instant timestamp) {
        this.suppressedSystemTimestamp = timestamp;
    }

    // ═══════════════════════════════════════════════════════════
    // 一轮请求的开销侧：钩子 + 遥测（对齐 AssistantStreamExecutor:67-95）
    // ═══════════════════════════════════════════════════════════

    /** 一轮 provider 请求开始：打开 {@code llm.request} 跨度（#94 起本体在 {@link PiLaneRequestTelemetry}）。 */
    void beginRequest(LaneState lane, List<Message> messages) {
        requestTelemetry.beginRequest(lane, messages.size(), toolRecords.toolCount(), stepIndex);
    }

    /** 原始帧旁路：{@code lane.partial} 与 token 记账（#94 起本体在 {@link PiLaneRequestTelemetry}）。 */
    void noteStreamEvent(StreamEvent event) {
        requestTelemetry.noteStreamEvent(ctx.requireLane(laneName), event);
    }

    @Override
    public void emit(PiLoop.Event event) {
        // 事件通往宿主的**唯一漏斗**。package B（原 docs/31 §8.23）起它全程串行化：工具批次
        // 真并发后，工具的 update 回调在**各自的工具线程**上直呼这里，而宿主消费者
        // （会话事件、TUI/RPC/web、记录发射）此前都按单线程写。加锁即把它们还原成 pi 的
        // 运行时语义 —— pi 是 JS 单线程 + 逐个 await emit，并发只存在于工具体；
        // synchronized 可重入，消费者内部再回调 emit 不会自锁。
        synchronized (emitLock) {
            if (event instanceof PiLoop.Event.MessageStart start) {
                onMessageStart(start.message());
            }
            if (event instanceof PiLoop.Event.MessageEnd end) {
                // docs/15 裁决①：message_end 替换臂（pi emitMessageEnd +
                // _replaceMessageInPlace 的对位）。宿主事件在 flush 之后、Message 不可变
                // —— 唯一能保语义的缝在这里：换引用则工作副本/transcript/下游事件三处
                // 同换（onMessageEnd 与 downstream 都拿替换后的事件）。
                var replaced = ctx.hookSystem().fireMessageEnd(laneName,
                    new MessageEndContext(laneName, ctx.requireLane(laneName).runId,
                        end.message()));
                if (replaced != null) {
                    end = new PiLoop.Event.MessageEnd(replaced);
                    event = end;
                }
                onMessageEnd(end.message());
            }
            if (downstream != null) {
                downstream.emit(event);
            }
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
        // state.messages 的（原 docs/31 §4.2）。
        lane.messages.add(message);
        if (alreadyPresent.contains(message)) {
            return;
        }
        if (message instanceof Message.SystemMessage system
                && suppressedSystemTimestamp != null
                && suppressedSystemTimestamp.equals(system.timestamp())) {
            suppressedSystemTimestamp = null;
            return;
        }
        switch (message) {
            case Message.AssistantMessage assistant -> {
                // 事件跟踪的「本次运行最后见到的助手消息」（pi
                // {@code _lastAssistantMessage}，{@code agent-session.ts:636,691}）——
                // post-run 压缩检查看的是它，**不是**对工作副本的扫描：一轮没发过
                // 助手消息的运行收口时为 null（副本里可能躺着上一轮的）。
                this.lastAssistant = assistant;
                toolRecords.onAssistantSettled(assistant);
                // 成功收尾（非 error/非 length）⇒ 溢出恢复闩锁复位
                // （pi {@code agent-session.ts:694-696}；package 3c）。
                StopReason stopReason = assistant.stopReason();
                if (stopReason != StopReason.ERROR && stopReason != StopReason.LENGTH) {
                    lane.overflowRecoveryAttempted = false;
                }
                // 非 error 收尾 ⇒ 重试链成功终止：发 auto_retry_end{success:true,
                // attempt} 并清零计数（pi {@code agent-session.ts:698-706}；
                // package 3d，{@code 原 docs/31 §8.22}）。判据只排除 error ——
                // **length 也复位**（pi 原文是 `stopReason !== "error"`）。
                if (stopReason != StopReason.ERROR && lane.retryAttempt > 0) {
                    int attempt = lane.retryAttempt;
                    lane.retryAttempt = 0;
                    ctx.retryObserver().onAutoRetryEnd(true, attempt, null);
                }
                // 循环可能把被中断的一轮改写成 aborted（PiLoopRunner.markAborted）。
                // lane.partial 必须跟着走：determineOutcome 与 lastAssistantMessage 都读它，
                // 不同步的话 abort 会被记成 completed。errorMessage 同路同步
                // （文本如今由**生产者**落定 —— StreamPartialBuilder.emitError /
                // StreamError.settle，C 批次 原 docs/55 §6.3 —— 宿主 E2E 与
                // 重试白名单分类器读的都是这条消息）。
                if (lane.partial != null) {
                    lane.partial = lane.partial
                        .withStopReason(assistant.stopReason())
                        .withErrorMessage(assistant.errorMessage());
                }
                var durationMs = requestTelemetry.llmDurationMs();
                var entry = append(lane, message);
                emitAssistantRecords(lane, entry, assistant, durationMs);
                requestTelemetry.endRequest(lane, assistant, durationMs);
                lane.newestOwn = HarnessUtils.deriveNewestOwn(lane);
            }
            case Message.ToolResultMessage result ->
                toolRecords.emitToolRecords(lane, append(lane, message), result);
            default -> {
                // 批 26（docs/37 T1）：custom 队列项的 user 投影落盘为
                // Entry.CustomMessage（pi agent-session.ts:1147-1155——message_end
                // 处理器把 role==="custom" 落成 custom_message 条目）。
                var custom = lane.customOrigins.remove(message);
                if (custom != null) {
                    appendCustom(lane, message, custom);
                } else {
                    append(lane, message);
                }
            }
        }
        // 状态发布：旧路径在 AgentHarness 的每个 action 后发布，
        // 新驱动的等价点是「每条 entry 产生后」。少了这一步，会话快照会停在
        // 起手时的 token 数（AgentSessionToolIntegrationTest 抓的就是这个）。
        ctx.publishState(laneName);
    }

    /** 建 entry 并挂上车道。entry 一旦产生就直接进 transcript，没有中间态。 */
    private Entry.Message append(LaneState lane, Message message) {
        // 叶 id 由 appendEntry 在锁下给 —— 工具线程的钩子标记会与这里抢同一个叶。
        // 运行中写入 ⇒ 记为 deferred（原 docs/22 D3）。用户 prompt 由
        // RunLifecycle.startRun() 在起手时写入，不走这里，语义不受影响。
        return lane.appendDeferredEntry((seq, parentId) -> new Entry.Message(
            UUID.randomUUID().toString(), 0, parentId, null, message, null));
    }

    /**
     * 批 26（docs/37 T1）：custom 队列项的落盘臂——载荷取自队列项
     * （customType/content/display/details），时间戳取自 user 投影消息。
     */
    private Entry.CustomMessage appendCustom(LaneState lane, Message message,
                                             LaneInfo.QueuedItem.Custom custom) {
        // 生产者（PiLaneEngine.toMessages 的 custom 臂）恒造 UserMessage。
        var timestamp = message instanceof Message.UserMessage user ? user.timestamp() : null;
        return lane.appendDeferredEntry((seq, parentId) -> new Entry.CustomMessage(
            UUID.randomUUID().toString(), 0, parentId, timestamp,
            custom.customType(), custom.content(), custom.display(), custom.details()));
    }

    // ═══════════════════════════════════════════════════════════
    // 记录日志（旁路审计，原 docs/28 选项 C）
    // ═══════════════════════════════════════════════════════════

    /** 助手步的 {@code StepAttempt} + {@code UsageRecord}。 */
    private void emitAssistantRecords(LaneState lane, Entry.Message entry,
                                      Message.AssistantMessage assistant, long durationMs) {
        int attempt = stepIndex++;
        lane.records.add(new LaneRecord.StepAttempt(
            UUID.randomUUID().toString(), 0, laneName, null, lane.runId,
            StepKind.ASSISTANT, attempt, entry.id(), null,
            RunSpanFactory.modelLabel(ctx.model().get()), assembledMessageCount,
            toolRecords.toolCount(), RunSpanFactory.thinkingLabel(ctx.thinkingLevel().get()), durationMs));
        // 无条件发射（原 docs/21）：零 token 的一轮（error / abort）恰恰是折叠需要 stopReason
        // 的那种情形，按 tokens>0 设门槛会把它丢掉。
        // A3（原 docs/42 步 6）：记录带的是**终局消息的全量 usage** —— pi 的
        // session-manager.ts:1152 起就整只传 Usage 对象（appendUsage(usage: Usage)）。
        // 终局消息的 usage 由 fromPartial 从 partial 携带的 UsageInfo 投影（3a），
        // B41 兜零值 ⇒ 恒非 null。旧代码这里的 Usage.of(i, o) 把
        // cacheRead/cacheWrite/cacheWrite1h/reasoning/cost 整段丢掉 ⇒
        // 会话账（SessionState.applyRecord 读的正是这条记录）四分量与 costTotal 恒 0。
        // D6（原 docs/12 §6）：usage 落成 pi 的**原生 usage 条目**，不再落成 custom 审计行。
        // pi 的 UsageEntry（session-manager.ts:80-89）＝ 自由类别串 ＋ provider/model ＋
        // Usage 载荷；本仓的审计字段（runId/entryId/attempt/stopReason）搭同一条行的扩展键
        // —— pi 的 parseSessionEntries 只做 JSON.parse（:353-368），未知键照读不误。
        // ⚠️ 它现在是**一条 entry**：按 pi 的 _appendEntry 语义（:1191-1196）它推进车道叶，
        // 后续 entry 的 parentId 落在它身上。
        var model = ctx.model().get();
        lane.appendDeferredEntry((seq, parentId) -> new Entry.Usage(
            UUID.randomUUID().toString(), seq, parentId, Instant.now(),
            UsageCause.ASSISTANT.value(), model.provider(), model.modelName(),
            assistant.usage(), null,
            lane.runId, entry.id(), null, attempt, assistant.stopReason()));
        ctx.addTokens(requestTelemetry.inputTokens() + requestTelemetry.outputTokens());
    }
}
