package com.pijava.agent.harness;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.pijava.agent.compaction.CompactionService;
import com.pijava.agent.compaction.CompactionSettings;
import com.pijava.agent.entry.Entry;
import com.pijava.agent.hook.CompactionContext;
import com.pijava.agent.record.LaneRecord;
import com.pijava.agent.record.OperationOutcome;
import com.pijava.agent.record.StepKind;
import com.pijava.ai.message.Message;
import com.pijava.telemetry.SpanOptions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Compact a lane transcript: {@code before_compaction} hook, compaction span,
 * and the {@link Entry.Compaction} marker entry with the retained tail.
 *
 * <p>Extracted from the former step-chain executor in the agent-loop L1 cleanup
 * (docs/20 §8) to keep files under the 500-line limit.</p>
 */
final class CompactionExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(CompactionExecutor.class);

    private final ExecutionContext ctx;

    CompactionExecutor(ExecutionContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Compact the specified lane's transcript.
     *
     * <p>守卫是 pi {@code compact()} 的形状（{@code agent-session.ts:1963-1969}
     * 对 {@code prepareCompaction} 空返回的解释，判据在 {@code compaction.ts:638}）：
     * <b>空路径</b> ⇒ "Nothing to compact (session too small)"；<b>最后一条已是
     * compaction 标记</b> ⇒ "Already compacted"。pi-java 用同一个
     * {@link NothingToCompactException} 承载两种原因（异常类型是方言，错误文案
     * 归宿主命令面清点）。此前这里的 {@code size <= 1} 门槛在 pi 不存在 ——
     * 单条消息的转录在 pi 是可压缩的（切点扫到它自己）。</p>
     */
    void compact(String laneName, CompactionSettings settings) {
        var lane = ctx.requireLane(laneName);
        if (lane.transcript.isEmpty()) {
            throw new NothingToCompactException(laneName);
        }
        if (lane.transcript.getLast() instanceof Entry.Compaction) {
            throw new NothingToCompactException(laneName);
        }
        applyCompaction(laneName, lane, settings, (int) contextTokens(lane), "manual");
        ctx.publishState(laneName);
    }

    /**
     * pi {@code estimateContextTokens(context.messages).tokens} —— 用量优先的
     * 上下文估算（compaction.ts:215-243；操作数是车道工作副本 ≙ pi 的
     * {@code context.messages}）。压缩的三条路径（threshold / manual / overflow）
     * 共用这一个来源做 tokensBefore，正如 pi 三条路都经
     * {@code prepareCompaction}（{@code :667}）。
     */
    long contextTokens(LaneState lane) {
        return (long) com.pijava.agent.context.ContextUsageEstimator
            .estimateContextTokens(List.copyOf(lane.messages)).tokens();
    }

    /**
     * Compact when the token budget is exceeded (pi
     * {@code _compactBeforeNextAssistantResponse}, {@code agent-session.ts:542}).
     *
     * <p>Called from {@code prepareNextTurn} — the trigger point pi wraps inside
     * {@code prepareNextTurnWithContext} ({@code :557-577}) — <b>not</b> from the
     * request path ({@code docs/31 §4.2}).</p>
     *
     * <p><b>门形状（3b，docs/31 §8.20）</b>逐条对齐 pi
     * {@code _compactBeforeNextAssistantResponse}（{@code agent-session.ts:542-557}）：
     * 无模型 ⇒ 跳过；当前模型的 {@code contextWindow <= 0} ⇒ 跳过；
     * {@code !shouldCompact(estimateContextTokens(context.messages).tokens,
     * model.contextWindow, settings)} ⇒ 跳过。操作数是**当前模型**的窗口
     * （随 setModel 动态变），不是宿主静态配置 —— 旧的
     * {@code estimatedTokens <= ctx.maxInputTokens() - reserve} 把动态模型
     * 维度整个丢了，且估算读的是 transcript 字符（无用量项）。pi 的
     * {@code transcript.size() <= 1} 门槛同样是**发明**：pi 在这道门里没有
     * 长度判据，取而代之的是 {@code _runAutoCompaction} 内
     * {@code prepareCompaction} 的守卫（{@code compaction.ts:638}：空路径或
     * 末条已是 compaction ⇒ 不压），这里照搬。{@code settings == null} 是
     * pi-java 方言（pi 恒有设置），仍在最前。</p>
     *
     * @return whether a compaction ran (the caller must then hand the rebuilt
     *         messages back to the loop through {@code NextTurnUpdate.context})
     */
    boolean checkThreshold(String laneName, LaneState lane) {
        var settings = ctx.compactionSettings().get();
        if (settings == null) return false;
        var model = ctx.model().get();
        if (model == null) return false;
        int window = ctx.contextWindow(model);
        if (window <= 0) return false;
        long estimatedTokens = contextTokens(lane);
        if (!com.pijava.agent.context.ContextUsageEstimator
                .shouldCompact(estimatedTokens, window, settings)) {
            return false;
        }
        // pi prepareCompaction 的守卫住在 _runAutoCompaction 里（:2262-2265）：
        // 空路径或末条已是 compaction ⇒ 静默不压（返回 false，不抛）。
        if (lane.transcript.isEmpty()
                || lane.transcript.getLast() instanceof Entry.Compaction) {
            return false;
        }
        applyCompaction(laneName, lane, settings, (int) estimatedTokens, "threshold");
        return true;
    }

    /**
     * Fire before_compaction, compute the compacted transcript, and replace it.
     *
     * @param reason one of {@code "manual"} / {@code "threshold"} /
     *               {@code "overflow"} (pi {@code compactionReason})
     */
    void applyCompaction(String laneName, LaneState lane,
                         CompactionSettings settings, int estimatedTokens, String reason) {
        ctx.telemetry().incrementCounter("compactions", 1);
        int entriesBefore = lane.transcript.size();
        long start = System.nanoTime();
        String compactionEntryId = null;
        var span = (lane.runSpan != null ? lane.runSpan : ctx.telemetry())
            .openSpan(new SpanOptions("compaction.apply",
                java.util.Map.of("reason", reason, "estimatedTokens", estimatedTokens,
                    "entriesBefore", entriesBefore)));
        try {
            var compactCtx = new CompactionContext(laneName,
                List.copyOf(lane.transcript), estimatedTokens);
            var plan = ctx.hookSystem().fireBeforeCompaction(laneName, compactCtx);
            List<Entry> compacted;
            if (plan != null && !plan.keepEntries().isEmpty()) {
                compacted = plan.keepEntries();
            } else {
                compacted = compactTranscript(lane, settings);
            }
            lane.transcript.clear();
            lane.transcript.addAll(compacted);
            // 日志被整体替换 ⇒ 工作副本跟着重建（pi agent-session.ts:2357-2359 的
            // 「写 entry → buildSessionContext → state.messages = ...」）。只有重建这一条路：
            // 压缩从不原地改写消息，它换的是日志。
            HarnessUtils.rebuildLaneMessages(lane);
            // The builder path puts the fresh marker at the head; the hook-plan
            // path keeps caller-supplied entries and creates no marker.
            if (!compacted.isEmpty() && compacted.get(0) instanceof Entry.Compaction marker) {
                compactionEntryId = marker.id();
            }
            span.addAttribute("entriesAfter", lane.transcript.size());
            LOG.info("[agent] compaction lane={} reason={} tokensBefore={} entries {}->{}",
                laneName, reason, estimatedTokens, entriesBefore, lane.transcript.size());
        } finally {
            span.close();
        }
        emitCompactionRecords(laneName, lane, reason, compactionEntryId,
            (System.nanoTime() - start) / 1_000_000);
    }

    /**
     * Record the compaction in the lane's record log (docs/21 D5).
     *
     * <p>A mid-run compaction is just another step of the running operation,
     * so it only appends a {@code StepAttempt(COMPACTION)}. Storage rejects a
     * second open operation per lane, so opening one here would corrupt the
     * session — an idle compaction, which has no enclosing operation, emits
     * the full started/step/finished triple instead.</p>
     */
    private void emitCompactionRecords(String laneName, LaneState lane, String reason,
                                       String resultEntryId, long durationMs) {
        String entryId = resultEntryId == null ? "" : resultEntryId;
        if (lane.isRunning()) {
            lane.records.add(compactionAttempt(laneName, lane, lane.runId, reason, entryId, durationMs));
            return;
        }
        String opId = UUID.randomUUID().toString();
        lane.records.add(new LaneRecord.OperationStarted(opId, 0, laneName, null,
            HarnessUtils.lastEntryId(lane),
            new LaneRecord.OperationStarted.Compaction(null, entryId)));
        lane.records.add(compactionAttempt(laneName, lane, opId, reason, entryId, durationMs));
        lane.records.add(new LaneRecord.OperationFinished(
            UUID.randomUUID().toString(), 0, laneName, null, opId,
            OperationOutcome.COMPLETED, null, durationMs));
    }

    private static LaneRecord.StepAttempt compactionAttempt(String laneName, LaneState lane,
                                                           String runId, String reason,
                                                           String resultEntryId, long durationMs) {
        // Attempts are numbered per (run, step) series and must be consecutive.
        // Nothing enforces that any more (the record-log fold was retired,
        // docs/30); the run summary reads these numbers, so a gap would still
        // misreport the step count.
        int attempt = 0;
        for (var record : lane.records) {
            if (record instanceof LaneRecord.StepAttempt step
                    && step.step() == StepKind.COMPACTION
                    && java.util.Objects.equals(step.runId(), runId)) {
                attempt++;
            }
        }
        return new LaneRecord.StepAttempt(
            UUID.randomUUID().toString(), 0, laneName, null, runId,
            StepKind.COMPACTION, attempt, resultEntryId, reason,
            null, null, null, null, durationMs);
    }

    private List<Entry> compactTranscript(LaneState lane, CompactionSettings settings) {
        // tokensBefore 单一来源：pi 的三条路（threshold/manual/overflow）都从
        // prepareCompaction :667 的 estimateContextTokens 读，这里同形 —— 落库的
        // Entry.Compaction.tokensBefore 因此是「用量优先」值，与触发判据同源。
        var result = CompactionService.compact(lane.transcript, settings,
            ctx.summaryGenerator(), contextTokens(lane));
        var retainedTail = keptMessagesFrom(lane.transcript, result.firstKeptEntryId());
        var compactionEntry = new Entry.Compaction(
            UUID.randomUUID().toString(), lane.nextSeq(), HarnessUtils.lastEntryId(lane),
            Instant.now(), result.summary(), result.firstKeptEntryId(),
            retainedTail, (int) result.tokensBefore(), result.details(), result.usage());
        var kept = new ArrayList<Entry>();
        String firstKept = result.firstKeptEntryId();
        boolean seen = false;
        for (var entry : lane.transcript) {
            if (seen) {
                kept.add(entry);
            } else if (entry.id().equals(firstKept)) {
                kept.add(entry);
                seen = true;
            }
        }
        kept.add(0, compactionEntry);
        return kept;
    }

    private static List<Message> keptMessagesFrom(List<Entry> transcript, String firstKeptId) {
        List<Message> kept = new ArrayList<>();
        boolean seen = false;
        for (var entry : transcript) {
            if (entry.id().equals(firstKeptId)) {
                seen = true;
            }
            if (seen && entry instanceof Entry.Message msg) {
                kept.add(msg.message());
            }
        }
        return kept;
    }
}
