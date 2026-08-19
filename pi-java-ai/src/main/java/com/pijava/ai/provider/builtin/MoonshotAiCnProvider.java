package com.pijava.ai.provider.builtin;

import com.pijava.ai.provider.OpenAiCompatibleProvider;
import com.pijava.ai.provider.Protocol;
import com.pijava.ai.provider.ProviderConfig;

/**
 * Moonshot AI CN — OpenAI Chat Completions compatible.
 */
public final class MoonshotAiCnProvider extends OpenAiCompatibleProvider {

    @Override
    protected ProviderConfig config() {
        return ProviderConfig.single(
            "moonshotai-cn", "Moonshot AI CN", "https://api.moonshot.cn/v1",
            "MOONSHOT_API_KEY", Protocol.OPENAI_COMPLETIONS,
            ModelData.moonshotAiCnModels());
    }
}
