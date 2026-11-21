package com.pijava.ai.provider.builtin;

import java.util.List;

import com.pijava.ai.provider.BuiltinModelCatalogs;
import com.pijava.ai.provider.OpenAiCompatibleProvider;
import com.pijava.ai.provider.Protocol;
import com.pijava.ai.provider.ProviderConfig;

/**
 * Qwen Token Plan Team CN — OpenAI Chat Completions compatible.
 *
 * <p>docs/11 S2：显示名、端点与密钥链照 pi {@code qwen-token-plan-cn.ts:8-17}
 * 更新（旧名 {@code Qwen Token Plan CN}／aliyuncs 域名已过期）。</p>
 */
public final class QwenTokenPlanCnProvider extends OpenAiCompatibleProvider {

    private static final String BASE_URL =
        "https://token-plan.maas.qianwenaiapi.com/compatible-mode/v1";

    @Override
    protected ProviderConfig config() {
        return ProviderConfig.single(
            "qwen-token-plan-cn", "Qwen Token Plan Team CN", BASE_URL,
            List.of("QWEN_TOKEN_PLAN_TEAM_CN_API_KEY", "QWEN_TOKEN_PLAN_CN_API_KEY"),
            Protocol.OPENAI_COMPLETIONS,
            BuiltinModelCatalogs.catalog("qwen-token-plan-cn"));
    }
}
