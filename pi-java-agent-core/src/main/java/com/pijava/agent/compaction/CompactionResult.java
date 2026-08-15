package com.pijava.agent.compaction;

import java.util.Map;

import com.pijava.ai.Usage;

/**
 * Result of a compaction pass (aligned with pi {@code CompactionResult}). The
 * persisted entry is {@code Entry.Compaction} (§3.3 of the Phase 4 design).
 *
 * @param summary            generated summary of the discarded prefix
 * @param firstKeptEntryId   id of the first retained entry (drives the drop)
 * @param tokensBefore       tokens before compaction
 * @param estimatedTokensAfter estimated tokens after compaction, may be null
 * @param usage              usage of the summary-generation call, may be null
 * @param details            optional details map ({@code readFiles}/{@code modifiedFiles})
 */
public record CompactionResult(
    String summary,
    String firstKeptEntryId,
    long tokensBefore,
    Long estimatedTokensAfter,
    Usage usage,
    Map<String, Object> details
) {}