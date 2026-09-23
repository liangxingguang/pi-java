package com.pijava.ai.protocol;

import com.pijava.ai.api.ToolCallIdNormalizer;

/**
 * Anthropic 车道的 toolCall id 归一器（包 B14 步2，pi P17）。
 *
 * <p>pi {@code anthropic-messages.ts:1208-1210} —— 无门（Anthropic 车道恒跑）。</p>
 */
public final class AnthropicToolCallIds {
    private AnthropicToolCallIds() {}

    /** pi {@code anthropic-messages.ts:1208-1210} —— 无门（Anthropic 车道恒跑）。 */
    public static ToolCallIdNormalizer create() {
        return (id, target, source) -> {
            var replaced = id.replaceAll("[^a-zA-Z0-9_-]", "_");
            return replaced.length() > 64 ? replaced.substring(0, 64) : replaced;
        };
    }
}
