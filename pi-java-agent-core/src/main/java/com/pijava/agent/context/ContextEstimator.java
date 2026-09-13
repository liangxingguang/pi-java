package com.pijava.agent.context;

import java.util.List;

import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

/**
 * Token-count estimation for conversation context.
 *
 * <p><b>注意</b>：本类不是 pi {@code estimateContextTokens()} 的移植 —— 那是
 * 字符数 ÷ 3.5 的自造启发式，pi 用的是「最后一条有效 assistant 用量 +
 * 其后消息 ceil(chars/4)」（{@code compaction.ts:215-243}）。pi 形状的实现是
 * {@link ContextUsageEstimator}（3b，docs/31 §8.20），压缩/溢出触发的对齐
 * 都走那边。本类只服务 {@code StreamSimple} 的粗粒度溢出提示，与 pi 无对应物；
 * 旧 javadoc 的「Aligned with pi」是不实声明，3b 更正。</p>
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

        // Rough: remove oldest messages until we're under
        long current = estimated;
        int toRemove = 0;
        for (int i = 0; i < messages.size(); i++) {
            var msg = messages.get(i);
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
