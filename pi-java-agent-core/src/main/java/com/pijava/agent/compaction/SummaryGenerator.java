package com.pijava.agent.compaction;

import java.util.List;

import com.pijava.ai.Usage;
import com.pijava.ai.message.Message;

/**
 * LLM-driven summary generator (aligned with pi
 * {@code generateSummary}/{@code generateSummaryWithUsage}). The harness
 * drives this via its stream function; {@link #truncating()} provides a
 * deterministic placeholder until Phase 6 wires the real summarization flow.
 */
@FunctionalInterface
public interface SummaryGenerator {

    /** Summarize the compressed message list. */
    SummaryResult summarize(List<Message> compressed, String previousSummary,
                            String customInstructions, int reserveTokens);

    /** Summary text plus the usage of the generating call (may be null). */
    record SummaryResult(String text, Usage usage) {}

    /**
     * Deterministic fallback: a terse structural summary. Does not call the
     * LLM; used by the harness until the summarization prompt flow lands.
     */
    static SummaryGenerator truncating() {
        return (compressed, previousSummary, customInstructions, reserveTokens) -> {
            String text = previousSummary != null && !previousSummary.isBlank()
                ? previousSummary
                : "Compacted " + compressed.size() + " earlier message(s).";
            return new SummaryResult(text, null);
        };
    }
}