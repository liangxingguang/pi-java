package com.pijava.ai.provider.builtin;

import com.pijava.ai.provider.BuiltinModelCatalogs;
import com.pijava.ai.provider.OpenAiCompatibleProvider;
import com.pijava.ai.provider.Protocol;
import com.pijava.ai.provider.ProviderConfig;

/**
 * Xiaomi Token Plan AMS — OpenAI Chat Completions compatible.
 */
public final class XiaomiTokenPlanAmsProvider extends OpenAiCompatibleProvider {

    @Override
    protected ProviderConfig config() {
        return ProviderConfig.single(
            "xiaomi-token-plan-ams", "Xiaomi Token Plan AMS",
            "https://token-plan-ams.xiaomimimo.com/v1",
            "XIAOMI_TOKEN_PLAN_AMS_API_KEY", Protocol.OPENAI_COMPLETIONS,
            BuiltinModelCatalogs.catalog("xiaomi-token-plan-ams"));
    }
}
