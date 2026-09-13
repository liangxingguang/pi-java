package com.pijava.agent.harness;

import java.util.List;
import java.util.UUID;

import com.pijava.agent.compaction.CompactionSettings;
import com.pijava.agent.entry.Entry;
import com.pijava.agent.hook.RunContext;
import com.pijava.agent.hook.RunEndContext;
import com.pijava.agent.record.LaneRecord;
import com.pijava.agent.record.OperationOutcome;
import com.pijava.ai.message.Message;
import com.pijava.ai.thinking.ModelThinkingLevel;

/**
 * 一次运行的**起手与收口**（pi {@code agent.ts} 的 {@code runWithLifecycle} / {@code finishRun}）。
 *
 * <p>本类由旧的步进链执行器收窄而来：步进链（{@code peekAction} / {@code executeAction} /
 * {@code computeNextAction}）连同它服务的 {@code Action} 联合类型已删除，推进职责全归
 * {@link PiLoop}（{@code docs/31 §3.2}、{@code §6}）。留下的三件事都与驱动循环无关，
 * 重新实现只会引入漂移：</p>
 *
 * <ul>
 *   <li><b>起手</b>：{@code runId}、{@link ActiveRun}、{@code before_run} 钩子、
 *       prompt entry、{@code OperationStarted} 记录；</li>
 *   <li><b>收口</b>：{@code OperationFinished} 记录、{@code before_run_end} 钩子、
 *       run span 关闭、{@link ActiveRun} 卸下（车道回到空闲）；</li>
 *   <li><b>空闲操作</b>：{@code reset} / 手动压缩。</li>
 * </ul>
 */
final class RunLifecycle {

    private final ExecutionContext ctx;
    private final RunSpanFactory runSpans;

    RunLifecycle(ExecutionContext ctx) {
        this.ctx = ctx;
        this.runSpans = new RunSpanFactory(ctx);
    }

    // ═══════════════════════════════════════════════════════════
    // 起手
    // ═══════════════════════════════════════════════════════════

    /**
     * 用一个新 prompt 起一次运行（pi {@code Agent.prompt} 的前半）。
     *
     * <p>用户消息在这里落进 {@link LaneState#transcript}；{@code PiLoop} 随后还会为同一个
     * prompt 发一对 {@code message_start}/{@code message_end}，引擎按**引用**抑制重复写入。</p>
     *
     * @return 本次运行的 {@link ActiveRun}；调用方负责在收口时完成它的 {@code done}
     */
    ActiveRun startRun(String laneName, String prompt, List<PromptImage> images) {
        var lane = requireIdleLane(laneName);
        var run = begin(lane);
        lane.runSpan = runSpans.openRunSpan(laneName, lane, prompt.length());

        var userMessage = HarnessUtils.buildUserMessage(prompt, images);
        var promptList = List.<Message>of(userMessage);
        ctx.hookSystem().fireBeforeRun(laneName,
            new RunContext(laneName, lane.runId, promptList));

        lane.transcript.add(messageEntry(lane, userMessage));
        recordConfigChanged(laneName);

        // pi alignment: the operation id IS the runId (state.openOperationsByLane pairs
        // operation_finished.runId with operation_started.id) — a separate UUID would
        // never match and would leak an open operation on the lane.
        lane.records.add(new LaneRecord.OperationStarted(
            lane.runId, 0, laneName, null, null,
            new LaneRecord.OperationStarted.Run(promptList, List.of(), null, null)));
        ctx.incrementTurn();
        ctx.publishState(laneName);
        return run;
    }

    // ═══════════════════════════════════════════════════════════
    // 配置 entry —— 与字段赋值同处写（pi sessionManager.append*）
    // ═══════════════════════════════════════════════════════════

    /**
     * 等级变更时补写 {@link Entry.ThinkingLevelChange}（pi {@code agent-session.ts:1813-1829}）。
     *
     * <p>pi 的守卫是 {@code isChanging = effectiveLevel !== previousLevel}，且**只在非默认等级时**
     * 才写（{@code Off} 不入日志）—— 这里用 {@link LaneState#recordedThinking} 承担
     * 「上一次记的值」，于是首次运行补记、之后无变更不重复、setter 改过之后下一次运行也不再重复。
     * 这是 §4.1「配置 entry 与字段赋值同处」在 Java 侧的落法：字段仍是唯一真源，entry 只是它的
     * 审计副本。</p>
     */
    void recordConfigChanged(String laneName) {
        var lane = ctx.requireLane(laneName);
        String label = ctx.thinkingLevel().get() instanceof ModelThinkingLevel.Enabled en
            ? en.level().label() : null;
        if (java.util.Objects.equals(label, lane.recordedThinking)) {
            return;
        }
        lane.recordedThinking = label;
        if (label == null) {
            return;                       // 默认（off）不写 entry，与 pi 一致
        }
        lane.transcript.add(new Entry.ThinkingLevelChange(
            UUID.randomUUID().toString(), lane.nextSeq(), HarnessUtils.lastEntryId(lane),
            null, label));
    }

    /**
     * 模型切换的 entry（pi {@code agent-session.ts:1687}）。
     *
     * <p>pi 在 {@code setModel} 里**无条件**追加 —— 变更判定只作用于 {@code model_select}
     * 会话事件（{@code _emitModelSelect} 在相等时提前返回），entry 照写。这里照抄该形状。</p>
     */
    void recordModelChange(String laneName, com.pijava.ai.model.ModelId<?> model) {
        var lane = ctx.requireLane(laneName);
        lane.transcript.add(new Entry.ModelChange(
            UUID.randomUUID().toString(), lane.nextSeq(), HarnessUtils.lastEntryId(lane),
            java.time.Instant.now(), model.provider(), model.modelName()));
    }

    /**
     * 从 transcript 尾部续跑（pi {@code runAgentLoopContinue}）：不写新的用户 entry，
     * 直接进助手流。自动重试与「上一轮以错误收尾」的续跑都走这里。
     */
    ActiveRun startContinue(String laneName) {
        var lane = requireIdleLane(laneName);
        if (lane.transcript.isEmpty()) {
            throw new IllegalStateException("Cannot continue: no messages in context");
        }
        var last = lane.lastEntry();
        if (last instanceof Entry.Message m
                && m.message() instanceof Message.AssistantMessage) {
            throw new IllegalStateException("Cannot continue from message role: assistant");
        }
        var run = begin(lane);
        lane.runSpan = runSpans.openRunSpan(laneName, lane, 0);
        ctx.hookSystem().fireBeforeRun(laneName,
            new RunContext(laneName, lane.runId, List.of()));
        lane.records.add(new LaneRecord.OperationStarted(
            lane.runId, 0, laneName, null, null,
            new LaneRecord.OperationStarted.Run(List.of(), List.of(), null, null)));
        ctx.incrementTurn();
        ctx.publishState(laneName);
        return run;
    }

    /** 起手的公共部分：车道必须空闲，随后装上新运行并清掉上一次运行留下的快照。 */
    private ActiveRun begin(LaneState lane) {
        if (lane.isRunning()) {
            throw new IllegalStateException(
                "Cannot start run: lane " + lane.laneName + " is not idle");
        }
        lane.runId = UUID.randomUUID().toString();
        lane.partial = null;
        lane.newestOwn = null;
        lane.runStartNanos = System.nanoTime();
        var run = ActiveRun.start();
        lane.activeRun = run;
        return run;
    }

    private LaneState requireIdleLane(String laneName) {
        return ctx.requireLane(laneName);
    }

    private static Entry.Message messageEntry(LaneState lane, Message message) {
        return new Entry.Message(UUID.randomUUID().toString(), lane.nextSeq(),
            HarnessUtils.lastEntryId(lane), null, message, null);
    }

    // ═══════════════════════════════════════════════════════════
    // 收口
    // ═══════════════════════════════════════════════════════════

    /**
     * 终局收口：写 {@code OperationFinished}、跑 {@code before_run_end}、关 run span、
     * 卸下 {@link ActiveRun} 把车道收回空闲。
     *
     * @param outcome {@code "completed"} / {@code "aborted"} / {@code "error"} / …
     *                （{@link HarnessUtils#determineOutcome} 的取值）
     */
    void finishRun(String laneName, String outcome) {
        var lane = ctx.requireLane(laneName);
        lane.records.add(new LaneRecord.OperationFinished(
            UUID.randomUUID().toString(), 0, laneName, null, lane.runId,
            outcome(outcome), null, RunSpanFactory.runDurationMs(lane)));
        ctx.hookSystem().fireBeforeRunEnd(laneName,
            new RunEndContext(laneName, lane.runId, outcome));
        runSpans.closeRunSpan(lane, outcome);
        lane.activeRun = null;
        ctx.publishState(laneName);
    }

    /**
     * 运行结局标签 → {@link OperationOutcome}。
     *
     * <p>{@code "error"} 是 {@link HarnessUtils#determineOutcome} 的取值；漏掉它会让
     * 出错的运行被记成 COMPLETED（span 的属性写着 error，记录写着 COMPLETED，两者矛盾），
     * 而 {@code SnapshotService.faulted} 正是靠 FAILED 判定的。</p>
     */
    private static OperationOutcome outcome(String value) {
        return switch (value) {
            case "aborted" -> OperationOutcome.ABORTED;
            case "error", "failed" -> OperationOutcome.FAILED;
            case "declined" -> OperationOutcome.DECLINED;
            default -> OperationOutcome.COMPLETED;
        };
    }

    // ═══════════════════════════════════════════════════════════
    // 空闲操作
    // ═══════════════════════════════════════════════════════════

    /** Clear lane transcript, queues, and run state (pi Agent.reset alignment). */
    void reset(String laneName) {
        var lane = ctx.requireLane(laneName);
        if (lane.isRunning()) {
            throw new IllegalStateException(
                "Cannot reset: lane " + laneName + " is running");
        }
        synchronized (lane) {
            lane.transcript.clear();
            // 工作副本跟着清（pi Agent.reset：this._state.messages = []，agent.ts:338）。
            lane.messages.clear();
            lane.records.clear();
            lane.partial = null;
            lane.newestOwn = null;
            lane.runId = null;
            lane.recordedThinking = null;
            lane.runSpan = null;
            lane.runStartNanos = 0;
            lane.steerQueue.clear();
            lane.followUpQueue.clear();
            lane.nextRunQueue.clear();
        }
    }

    /** Compact the specified lane's transcript (delegated to {@link CompactionExecutor}). */
    void compact(String laneName, CompactionSettings settings) {
        new CompactionExecutor(ctx).compact(laneName, settings);
    }

    // ═══════════════════════════════════════════════════════════
    // 恢复（resume）
    // ═══════════════════════════════════════════════════════════

    /**
     * Seed a lane transcript from a persisted session on resume. No-op when
     * the lane already has entries (Phase 4 recovery).
     */
    void seedTranscript(String laneName, List<Entry> entries) {
        var lane = ctx.requireLane(laneName);
        if (!lane.transcript.isEmpty()) {
            return;
        }
        lane.transcript.addAll(entries);
        // 恢复出让「上次记过什么」与既有日志一致，否则恢复后的首次运行会把一条已经在
        // 日志里的 ThinkingLevelChange 再写一遍（docs/31 §4.1）。
        lane.recordedThinking = lastRecordedThinking(lane);
        // 工作副本从播种的日志重建 —— resume 是「首次填充」那一类重建点
        // （pi sdk.ts:376 的启动恢复正是 5 个 sync 点之一，docs/31 §4.2）。
        HarnessUtils.rebuildLaneMessages(lane);
    }

    /** 日志里最后一条 {@code ThinkingLevelChange} 的标签；没有则为 {@code null}。 */
    private static String lastRecordedThinking(LaneState lane) {
        for (int i = lane.transcript.size() - 1; i >= 0; i--) {
            if (lane.transcript.get(i) instanceof Entry.ThinkingLevelChange change) {
                return change.thinkingLevel();
            }
        }
        return null;
    }

    /**
     * Load a lane's persisted record log on resume (docs/30 §4.1).
     *
     * <p>State is <b>replaced, never merged</b> — this is a resume, so the lane
     * is empty. Only the log itself is loaded: orchestration state is no longer
     * reconstructed from it. The record log is a pure audit side channel
     * (docs/28 option C), and pi forbids inferring state from it
     * ({@code harness.md:1317} invariant 5: <i>"no value history exists to
     * fold"</i>), so nothing here derives run id or queues out of records.</p>
     *
     * <p>The lane comes back <b>idle</b> ({@code activeRun == null}). Any operation a
     * crash left open was settled at the resume boundary before this call
     * ({@code SessionPersistence.settleOpenOperation}), so storage holds no open
     * operation and the next run may open its own.</p>
     *
     * <p>Queues start <b>empty</b>: pi keeps them in-process, so a crash loses
     * them. Rebuilding them from the log would re-inject a half-finished prompt
     * into an unrelated later run (docs/30 §4.3). {@code newestOwn} likewise
     * starts {@code null} — it is derived from the run's own output
     * ({@code PiLaneSink.onMessageEnd}) long before any outcome is determined,
     * and {@link HarnessUtils#determineOutcome} is only ever called after that.</p>
     */
    void restoreRecords(String laneName, List<LaneRecord> records) {
        var lane = ctx.requireLane(laneName);
        synchronized (lane) {
            lane.records.clear();
            lane.records.addAll(records);
            lane.steerQueue.clear();
            lane.followUpQueue.clear();
            lane.nextRunQueue.clear();
            lane.queueSeq = 0;
            lane.runId = null;
            lane.newestOwn = null;
            lane.partial = null;
            lane.activeRun = null;
        }
        ctx.publishState(laneName);
    }

    /**
     * Remove the trailing error assistant entry from the lane transcript
     * (pi {@code _prepareRetry} keeps the errored message only in session
     * history, not in agent state), so a retry continues from the prior
     * context without re-prompting.
     */
    void dropTrailingErrorAssistant(String laneName) {
        var lane = ctx.requireLane(laneName);
        var entries = lane.transcript;
        if (entries.isEmpty()
                || !(entries.get(entries.size() - 1) instanceof Entry.Message m)
                || !"assistant".equals(m.message().role())) {
            return;
        }
        String stopReason = lane.newestOwn != null ? lane.newestOwn.stopReason() : null;
        if (HarnessUtils.isErrorStopReason(stopReason)) {
            entries.remove(entries.size() - 1);
            // 日志被改 ⇒ 工作副本跟着重建，否则重试会带着那条残缺的助手消息发出去。
            HarnessUtils.rebuildLaneMessages(lane);
        }
    }
}
