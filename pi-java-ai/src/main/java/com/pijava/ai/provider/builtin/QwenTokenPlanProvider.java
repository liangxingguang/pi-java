package com.pijava.ai.provider.builtin;

import java.util.List;

import com.pijava.ai.provider.BuiltinModelCatalogs;
import com.pijava.ai.provider.OpenAiCompatibleProvider;
import com.pijava.ai.provider.Protocol;
import com.pijava.ai.provider.ProviderConfig;

/**
 * Qwen Token Plan Team (国际) — OpenAI Chat Completions compatible.
 *
 * <p>docs/11 S1：密钥链照 pi {@code qwen-token-plan.ts:8-17} —— 专属键先行，
 * 区域共享键回落（{@code env-api-keys.ts:84-90}）。</p>
 */
public final class QwenTokenPlanProvider extends OpenAiCompatibleProvider {

    private static final String BASE_URL =
        "https://token-plan.maas.qwencloudapi.com/compatible-mode/v1";

    @Override
    protected ProviderConfig config() {
        return ProviderConfig.single(
            "qwen-token-plan", "Qwen Token Plan Team", BASE_URL,
            List.of("QWEN_TOKEN_PLAN_TEAM_API_KEY", "QWEN_TOKEN_PLAN_API_KEY"),
            Protocol.OPENAI_COMPLETIONS,
            BuiltinModelCatalogs.catalog("qwen-token-plan"));
    }
}
