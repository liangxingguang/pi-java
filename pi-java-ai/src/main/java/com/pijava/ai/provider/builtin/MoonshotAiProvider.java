package com.pijava.ai.provider.builtin;

import com.pijava.ai.provider.OpenAiCompatibleProvider;
import com.pijava.ai.provider.Protocol;
import com.pijava.ai.provider.ProviderConfig;

/**
 * Moonshot AI — OpenAI Chat Completions compatible.
 */
public final class MoonshotAiProvider extends OpenAiCompatibleProvider {

    @Override
    protected ProviderConfig config() {
        return ProviderConfig.single(
            "moonshotai", "Moonshot AI", "https://api.moonshot.ai/v1",
            "MOONSHOT_API_KEY", Protocol.OPENAI_COMPLETIONS,
            ModelData.moonshotAiModels());
    }
}
