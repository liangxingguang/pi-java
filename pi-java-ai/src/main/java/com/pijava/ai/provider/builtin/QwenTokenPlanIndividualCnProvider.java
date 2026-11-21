package com.pijava.ai.provider.builtin;

import java.util.List;

import com.pijava.ai.provider.BuiltinModelCatalogs;
import com.pijava.ai.provider.OpenAiCompatibleProvider;
import com.pijava.ai.provider.Protocol;
import com.pijava.ai.provider.ProviderConfig;

/**
 * Qwen Token Plan Individual CN (个人版, 中国) — OpenAI Chat Completions compatible.
 *
 * <p>docs/11 S4：中国端点 ＋ 同一白名单（{@code qwen-token-plan-individual-cn.ts}）。</p>
 */
public final class QwenTokenPlanIndividualCnProvider extends OpenAiCompatibleProvider {

    private static final String BASE_URL =
        "https://token-plan.maas.qianwenaiapi.com/compatible-mode/v1";

    @Override
    protected ProviderConfig config() {
        return ProviderConfig.single(
            "qwen-token-plan-individual-cn", "Qwen Token Plan Individual CN", BASE_URL,
            List.of("QWEN_TOKEN_PLAN_INDIVIDUAL_CN_API_KEY", "QWEN_TOKEN_PLAN_CN_API_KEY"),
            Protocol.OPENAI_COMPLETIONS,
            BuiltinModelCatalogs.catalog("qwen-token-plan-individual-cn"));
    }
}
