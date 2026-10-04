package com.pijava.ai.protocol;

import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevel;

/**
 * Mistral 车道的思考配置换算 —— pi {@code api/mistral-conversations.ts:898-916} 的三件套
 * 的逐字移植（包 B155，{@code docs/10}）。
 *
 * <p>两个 helper 是**互斥的两支**：命中 {@link #usesReasoningEffort} 的 id 白名单 ⇒ 发
 * {@code reasoningEffort}；否则（只要模型是 reasoning）⇒ 发 {@code promptMode:"reasoning"}。</p>
 *
 * <p>⚠️ 白名单在本仓内置目录**没有命中**（那里只有 {@code mistral-large}／{@code mistral-small}）
 * ⇒ {@code reasoningEffort} 支只有 {@code models.json} 写一个白名单 id 时才可达；
 * {@code promptMode} 支则**生产可达**（{@code mistral-large} 带 THINKING）。见 {@code docs/10 §1}。</p>
 */
final class MistralThinking {

    private MistralThinking() {}

    /** pi {@code :898-905} —— 这四个 id 用 {@code reasoningEffort} 而不是 {@code promptMode}。 */
    static boolean usesReasoningEffort(ModelInfo model) {
        var id = model.id().modelName();
        return id.equals("mistral-small-2603")
            || id.equals("mistral-small-latest")
            || id.startsWith("mistral-medium-")
            || id.equals("zai-glm-5-2");
    }

    /** pi {@code :907-909}。 */
    static boolean usesPromptModeReasoning(ModelInfo model) {
        return model.capabilities().contains(ModelCapability.THINKING)
            && !usesReasoningEffort(model);
    }

    /**
     * pi {@code :911-916} {@code mapReasoningEffort} —— 目录映射值优先，缺席回落
     * {@code "high"}（pi 的 {@code thinkingLevelMap?.[level] ?? "high"}，**不校验**
     * {@code "none"|"high"} 闭集）。
     */
    static String effort(ModelInfo model, ThinkingLevel level) {
        return model.thinkingLevelMap().mapped(ModelThinkingLevel.of(level)).orElse("high");
    }
}
