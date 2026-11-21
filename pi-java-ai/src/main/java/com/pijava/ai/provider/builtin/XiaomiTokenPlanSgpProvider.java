package com.pijava.ai.provider.builtin;

import com.pijava.ai.provider.BuiltinModelCatalogs;
import com.pijava.ai.provider.OpenAiCompatibleProvider;
import com.pijava.ai.provider.Protocol;
import com.pijava.ai.provider.ProviderConfig;

/**
 * Xiaomi Token Plan SGP — OpenAI Chat Completions compatible.
 */
public final class XiaomiTokenPlanSgpProvider extends OpenAiCompatibleProvider {

    @Override
    protected ProviderConfig config() {
        return ProviderConfig.single(
            "xiaomi-token-plan-sgp", "Xiaomi Token Plan SGP",
            "https://token-plan-sgp.xiaomimimo.com/v1",
            "XIAOMI_TOKEN_PLAN_SGP_API_KEY", Protocol.OPENAI_COMPLETIONS,
            BuiltinModelCatalogs.catalog("xiaomi-token-plan-sgp"));
    }
}
