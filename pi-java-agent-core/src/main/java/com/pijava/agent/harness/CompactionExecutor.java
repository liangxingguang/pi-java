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
 * <p>Extracted from {@link ActionExecutor} in the agent-loop L1 cleanup
 * (docs/20 §8) to keep files under the 500-line limit.</p>
 */
final class CompactionExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(CompactionExecutor.class);

    private final ExecutionContext ctx;

    CompactionExecutor(ExecutionContext ctx) {
        this.ctx = ctx;
    }

    /** Compact the specified lane's transcript. */
    void compact(String laneName, CompactionSettings settings) {
        var lane = ctx.requireLane(laneName);
        if (lane.transcript.size() <= 1) {
            throw new NothingToCompactException(laneName);
        }
        applyCompaction(laneName, lane, settings,
            CompactionService.estimateTokens(lane.transcript), "manual");
        ctx.publishState(laneName);
    }

    /** Compact when the token budget is exceeded (auto-compaction). */
    void checkAutoCompact(String laneName, LaneState lane) {
        var settings = ctx.compactionSettings().get();
        if (settings == null) return;
        if (lane.transcript.size() <= 1) return;
        int estimatedTokens = CompactionService.estimateTokens(lane.transcript);
        if (settings.enabled() && estimatedTokens > ctx.maxInputTokens() - settings.reserveTokens()) {
            applyCompaction(laneName, lane, settings, estimatedTokens, "threshold");
        }
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
        if (!(lane.phase instanceof RunPhase.Idle)) {
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
        // Attempts are numbered per (run, step) series and must be consecutive
        // — validateRecordLog rejects a gap (docs/21 §3.4).
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
            null, null, null, null, durationMs, null);
    }

    private List<Entry> compactTranscript(LaneState lane, CompactionSettings settings) {
        var result = CompactionService.compact(lane.transcript, settings, ctx.summaryGenerator());
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
