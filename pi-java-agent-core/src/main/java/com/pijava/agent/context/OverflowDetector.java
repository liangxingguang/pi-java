package com.pijava.agent.context;

import com.pijava.ai.stream.StreamEvent;

/**
 * Triple-detection for context overflow.
 *
 * <p>Aligned with pi {@code isContextOverflow()}. Three checks:
 * <ol>
 *   <li>Error message pattern matching (e.g. "context length", "too long")</li>
 *   <li>Token count comparison (estimated tokens > model window × margin)</li>
 *   <li>Zero output + stopReason "length"</li>
 * </ol>
 *
 * <p>Phase 2a: defined and unit-tested. Integrated in Phase 2c
 * (compaction flow).</p>
 */
public final class OverflowDetector {

    private OverflowDetector() {}

    /**
     * Check if a stream error/termination indicates context overflow.
     *
     * @param error          the exception (null if none)
     * @param stopReason     the stop reason from StreamDone or StreamError
     * @param usage          the final usage info (null if unavailable)
     * @param maxInputTokens the model's max input token count
     * @return true if the evidence indicates a context overflow
     */
    public static boolean isOverflow(Throwable error,
                                      String stopReason,
                                      StreamEvent.UsageInfo usage,
                                      int maxInputTokens) {
        // 1. Error message patterns
        if (error != null) {
            String msg = error.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("context length")
                        || lower.contains("too long")
                        || lower.contains("maximum context")
                        || lower.contains("token limit")
                        || lower.contains("reduce the length")) {
                    return true;
                }
            }
        }

        // 2. Token count comparison
        if (usage != null && maxInputTokens > 0) {
            long totalTokens = usage.inputTokens() + usage.outputTokens();
            if (totalTokens > maxInputTokens) {
                return true;
            }
        }

        // 3. Zero output + length stop reason
        if ("length".equals(stopReason) && usage != null && usage.outputTokens() == 0) {
            return true;
        }

        return false;
    }
}
