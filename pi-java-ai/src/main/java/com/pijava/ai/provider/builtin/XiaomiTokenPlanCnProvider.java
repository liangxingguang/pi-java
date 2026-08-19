package com.pijava.ai.provider.builtin;

import com.pijava.ai.provider.OpenAiCompatibleProvider;
import com.pijava.ai.provider.Protocol;
import com.pijava.ai.provider.ProviderConfig;

/**
 * Xiaomi Token Plan CN — OpenAI Chat Completions compatible.
 */
public final class XiaomiTokenPlanCnProvider extends OpenAiCompatibleProvider {

    @Override
    protected ProviderConfig config() {
        return ProviderConfig.single(
            "xiaomi-token-plan-cn", "Xiaomi Token Plan CN", "https://token-plan-cn.xiaomimimo.com/v1",
            "XIAOMI_TOKEN_PLAN_CN_API_KEY", Protocol.OPENAI_COMPLETIONS,
            ModelData.xiaomiTokenPlanCnModels());
    }
}
