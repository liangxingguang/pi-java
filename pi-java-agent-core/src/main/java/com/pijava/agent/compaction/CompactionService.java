package com.pijava.agent.compaction;

import java.util.ArrayList;
import java.util.List;

import com.pijava.agent.entry.Entry;

/**
 * Service that executes compaction on a transcript.
 * Works with {@link List}&lt;{@link Entry}&gt; — does not depend on LaneState internals.
 */
public final class CompactionService {

    private CompactionService() {}

    /**
     * Build a compacted transcript according to the given settings.
     *
     * @param transcript the full transcript to compact
     * @param settings   compaction parameters
     * @param nextSeq    next sequence number for the compaction entry
     * @param parentId   parent ID for the compaction entry
     * @return the compacted transcript (new list)
     */
    public static List<Entry> compact(List<Entry> transcript, CompactionSettings settings,
                                       long nextSeq, String parentId) {
        if (transcript.size() <= 1) {
            throw new IllegalStateException("Nothing to compact: transcript too small");
        }

        int entriesBefore = transcript.size();
        int keepCount = Math.max(1, (int) (entriesBefore * settings.retentionRatio()));
        int startIdx = Math.max(0, entriesBefore - keepCount);

        var compacted = new ArrayList<>(transcript.subList(startIdx, entriesBefore));

        // Create compaction entry recording what happened
        var compactionEntry = new Entry.Compaction(
            Entry.newHeader(nextSeq, parentId),
            "overflow", entriesBefore, compacted.size());

        // Prepend compaction entry
        compacted.add(0, compactionEntry);
        return List.copyOf(compacted);
    }

    /**
     * Estimate token count for a list of entries (rough heuristic: ~4 chars per token).
     */
    public static int estimateTokens(List<Entry> entries) {
        long chars = 0;
        for (var entry : entries) {
            if (entry instanceof Entry.Message msg) {
                for (var block : msg.blocks()) {
                    chars += block.toString().length();
                }
            }
        }
        return (int) (chars / 4);
    }
}
