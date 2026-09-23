package com.pijava.ai.protocol;

import com.pijava.ai.api.ToolCallIdNormalizer;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.utils.ShortHash;

import java.util.HashMap;
import java.util.Map;

/**
 * Mistral 车道的 toolCall id 归一器（包 B14 步2，pi P22）。
 *
 * <p>pi {@code mistral-conversations.ts:232-268}（:141 每请求 create）——
 * 有状态 idMap/reverseMap，**不做线程同步**（每次请求新建、单线程使用，pi 同形）。</p>
 */
public final class MistralToolCallIds {

    private static final int LENGTH = 9; // pi MISTRAL_TOOL_CALL_ID_LENGTH

    private final Map<String, String> idMap = new HashMap<>();
    private final Map<String, String> reverseMap = new HashMap<>();

    private MistralToolCallIds() {}

    /** 每次请求新建一个（pi :141 在请求构建内调 createMistralToolCallIdNormalizer()）。 */
    public static ToolCallIdNormalizer create() {
        var state = new MistralToolCallIds();
        return state::normalize;
    }

    private String normalize(String id, ModelId<?> target, Message.AssistantMessage source) {
        var existing = idMap.get(id);
        if (existing != null) {
            return existing;
        }
        int attempt = 0;
        while (true) {
            var candidate = derive(id, attempt);
            var owner = reverseMap.get(candidate);
            if (owner == null || owner.equals(id)) {
                idMap.put(id, candidate);
                reverseMap.put(candidate, id);
                return candidate;
            }
            attempt++;
        }
    }

    /**
     * pi {@code mistral-conversations.ts:246-260} 的 {@code deriveMistralToolCallId}。
     *
     * <p>⚠️ 三处易走样（docs/47 §5）：① {@code normalized.length() === 9} 是**精确等于**；
     * ② {@code seedBase = normalized || id} ＝ normalized **空串**时回落原 id（JS 假值回落）；
     * ③ shortHash 输出恒字母数字 ⇒ replace 恒等但**照抄**；slice(0,9) 对短串恒等。</p>
     */
    private static String derive(String id, int attempt) {
        var normalized = id.replaceAll("[^a-zA-Z0-9]", "");
        if (attempt == 0 && normalized.length() == LENGTH) {
            return normalized;
        }
        var seedBase = normalized.isEmpty() ? id : normalized;
        var seed = attempt == 0 ? seedBase : seedBase + ":" + attempt;
        var hashed = ShortHash.of(seed).replaceAll("[^a-zA-Z0-9]", "");
        return hashed.length() > LENGTH ? hashed.substring(0, LENGTH) : hashed;
    }
}
