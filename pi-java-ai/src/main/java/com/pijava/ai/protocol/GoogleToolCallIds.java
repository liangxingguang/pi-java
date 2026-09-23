package com.pijava.ai.protocol;

import com.pijava.ai.api.ToolCallIdNormalizer;

/**
 * Google 车道的 toolCall id 归一器（包 B14 步2，pi P19）。
 *
 * <p>pi {@code google-shared.ts:193-198} —— 门读 model.id（模型名），关门 ⇒ 原样
 * （「返回原值是合法结果」的实例之一）。</p>
 */
public final class GoogleToolCallIds {
    private GoogleToolCallIds() {}

    /**
     * pi {@code google-shared.ts:193-198} —— 门读 model.id（模型名），复用
     * {@link GoogleMessageConverter#requiresToolCallId}（D6，别另造谓词）。
     */
    public static ToolCallIdNormalizer create() {
        return (id, target, source) -> {
            if (!GoogleMessageConverter.requiresToolCallId(target.modelName())) {
                return id;
            }
            var replaced = id.replaceAll("[^a-zA-Z0-9_-]", "_");
            return replaced.length() > 64 ? replaced.substring(0, 64) : replaced;
        };
    }
}
