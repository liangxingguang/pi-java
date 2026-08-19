package com.pijava.ai.provider.builtin;

import com.pijava.ai.provider.OpenAiCompatibleProvider;
import com.pijava.ai.provider.Protocol;
import com.pijava.ai.provider.ProviderConfig;

/**
 * Qwen Token Plan CN — OpenAI Chat Completions compatible.
 */
public final class QwenTokenPlanCnProvider extends OpenAiCompatibleProvider {

    private static final String BASE_URL =
        "https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1";

    @Override
    protected ProviderConfig config() {
        return ProviderConfig.single(
            "qwen-token-plan-cn", "Qwen Token Plan CN", BASE_URL,
            "QWEN_TOKEN_PLAN_CN_API_KEY", Protocol.OPENAI_COMPLETIONS,
            ModelData.qwenTokenPlanCnModels());
    }
}
