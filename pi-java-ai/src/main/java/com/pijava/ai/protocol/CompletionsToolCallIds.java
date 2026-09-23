package com.pijava.ai.protocol;

import com.pijava.ai.api.ToolCallIdNormalizer;
import com.pijava.ai.utils.ShortHash;
import com.pijava.ai.utils.ShortHash;

/**
 * OpenAI Completions 车道的 toolCall id 归一器（包 B14 步2，pi P20）。
 *
 * <p>pi {@code openai-completions.ts:1193-1215} —— {@code |} 复合 id 分支 ＋
 * {@code model.provider === "openai"} 的 40 字符截断门。</p>
 */
public final class CompletionsToolCallIds {
    private CompletionsToolCallIds() {}

    /** pi {@code openai-completions.ts:1193-1215}。 */
    public static ToolCallIdNormalizer create() {
        return (id, target, source) -> {
            if (id.contains("|")) {
                int separatorIndex = id.indexOf('|');
                var callId = id.substring(0, separatorIndex).replaceAll("[^a-zA-Z0-9_-]", "_");
                var itemId = id.substring(separatorIndex + 1).replaceAll("[^a-zA-Z0-9_-]", "_");
                var combinedId = itemId.length() > 0 ? callId + "_" + itemId : callId;
                if (combinedId.length() <= 40) {
                    return combinedId;
                }
                var hash = ShortHash.of(id).substring(0, 8);
                // JS slice(0, end) 对超界 end 截到串长；Java substring 会抛 ⇒ 显式 clamp
                int end = Math.max(1, 40 - hash.length() - 1);
                var prefix = callId.length() > end ? callId.substring(0, end) : callId;
                return prefix + "_" + hash;
            }
            if ("openai".equals(target.provider())) {
                return id.length() > 40 ? id.substring(0, 40) : id;
            }
            return id;
        };
    }
}
