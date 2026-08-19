package com.pijava.ai.provider.builtin;

import com.pijava.ai.provider.OpenAiCompatibleProvider;
import com.pijava.ai.provider.Protocol;
import com.pijava.ai.provider.ProviderConfig;

/**
 * Z.AI Coding CN — OpenAI Chat Completions compatible.
 */
public final class ZaiCodingCnProvider extends OpenAiCompatibleProvider {

    @Override
    protected ProviderConfig config() {
        return ProviderConfig.single(
            "zai-coding-cn", "Z.AI Coding CN", "https://open.bigmodel.cn/api/coding/paas/v4",
            "ZAI_CODING_CN_API_KEY", Protocol.OPENAI_COMPLETIONS,
            ModelData.zaiCodingCnModels());
    }
}
