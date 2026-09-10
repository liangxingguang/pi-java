package com.pijava.agent.harness;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.pijava.agent.compaction.CompactionService;
import com.pijava.agent.compaction.CompactionSettings;
import com.pijava.agent.entry.Entry;
import com.pijava.agent.hook.CompactionContext;
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
            CompactionService.estimateTokens(lane.transcript));
        ctx.publishState(laneName);
    }

    /** Compact when the token budget is exceeded (auto-compaction). */
    void checkAutoCompact(String laneName, LaneState lane) {
        var settings = ctx.compactionSettings().get();
        if (settings == null) return;
        if (lane.transcript.size() <= 1) return;
        int estimatedTokens = CompactionService.estimateTokens(lane.transcript);
        if (settings.enabled() && estimatedTokens > ctx.maxInputTokens() - settings.reserveTokens()) {
            applyCompaction(laneName, lane, settings, estimatedTokens);
        }
    }

    /** Fire before_compaction, compute the compacted transcript, and replace it. */
    void applyCompaction(String laneName, LaneState lane,
                         CompactionSettings settings, int estimatedTokens) {
        ctx.telemetry().incrementCounter("compactions", 1);
        int entriesBefore = lane.transcript.size();
        var span = (lane.runSpan != null ? lane.runSpan : ctx.telemetry())
            .openSpan(new SpanOptions("compaction.apply",
                java.util.Map.of("reason", "auto", "estimatedTokens", estimatedTokens,
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
            span.addAttribute("entriesAfter", lane.transcript.size());
            LOG.info("[agent] compaction lane={} tokensBefore={} entries {}->{}",
                laneName, estimatedTokens, entriesBefore, lane.transcript.size());
        } finally {
            span.close();
        }
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
