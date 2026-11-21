package com.pijava.ai.provider.builtin;

import java.util.List;

import com.pijava.ai.provider.BuiltinModelCatalogs;
import com.pijava.ai.provider.OpenAiCompatibleProvider;
import com.pijava.ai.provider.Protocol;
import com.pijava.ai.provider.ProviderConfig;

/**
 * Qwen Token Plan Individual (个人版, 国际) — OpenAI Chat Completions compatible.
 *
 * <p>docs/11 S3：与团队国际版共用端点，目录收窄为 13 个白名单模型
 * （{@code catalog/providers/qwen-token-plan-individual.ts}）。</p>
 */
public final class QwenTokenPlanIndividualProvider extends OpenAiCompatibleProvider {

    private static final String BASE_URL =
        "https://token-plan.maas.qwencloudapi.com/compatible-mode/v1";

    @Override
    protected ProviderConfig config() {
        return ProviderConfig.single(
            "qwen-token-plan-individual", "Qwen Token Plan Individual", BASE_URL,
            List.of("QWEN_TOKEN_PLAN_INDIVIDUAL_API_KEY", "QWEN_TOKEN_PLAN_API_KEY"),
            Protocol.OPENAI_COMPLETIONS,
            BuiltinModelCatalogs.catalog("qwen-token-plan-individual"));
    }
}
