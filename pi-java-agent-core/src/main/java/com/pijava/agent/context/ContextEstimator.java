package com.pijava.agent.context;

import java.util.List;

import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

/**
 * Token-count estimation for conversation context.
 *
 * <p>Aligned with pi {@code estimateContextTokens()}. Uses a character-based
 * heuristic: total characters ÷ 3.5 (mixed Chinese/English average).
 * Not exact — for overflow detection and compaction triggering only.</p>
 */
public final class ContextEstimator {

    private static final double CHARS_PER_TOKEN = 3.5;
    private static final double DEFAULT_SAFETY_MARGIN = 0.9;

    private ContextEstimator() {}

    /**
     * Estimate the total token count for a list of messages.
     */
    public static long estimateTokens(List<Message> messages) {
        long totalChars = 0;
        for (var msg : messages) {
            for (var block : msg.content()) {
                if (block instanceof ContentBlock.TextContent tc) {
                    totalChars += tc.text().length();
                }
            }
        }
        return Math.round(totalChars / CHARS_PER_TOKEN);
    }

    /**
     * Check if the message list is likely to overflow the model's context window.
     *
     * @param messages       the context messages
     * @param maxInputTokens the model's max input token count
     * @param safetyMargin   fraction of window to use (0.0–1.0, default 0.9)
     * @return estimated number of messages to compact, or 0 if within limits
     */
    public static int checkOverflow(List<Message> messages,
                                     int maxInputTokens,
                                     double safetyMargin) {
        long estimated = estimateTokens(messages);
        long limit = (long) (maxInputTokens * safetyMargin);
        if (estimated <= limit) return 0;

        // Rough: remove oldest non-system messages until we're under
        long current = estimated;
        int toRemove = 0;
        for (int i = 0; i < messages.size(); i++) {
            var msg = messages.get(i);
            if (msg instanceof Message.SystemMessage) continue;
            long msgTokens = estimateTokens(List.of(msg));
            current -= msgTokens;
            toRemove++;
            if (current <= limit) break;
        }
        return toRemove;
    }

    /** Convenience overload with default safety margin. */
    public static int checkOverflow(List<Message> messages, int maxInputTokens) {
        return checkOverflow(messages, maxInputTokens, DEFAULT_SAFETY_MARGIN);
    }
}
