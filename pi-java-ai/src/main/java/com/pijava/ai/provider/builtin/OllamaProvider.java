package com.pijava.ai.provider.builtin;

import com.pijava.ai.provider.OpenAiCompatibleProvider;
import com.pijava.ai.provider.Protocol;
import com.pijava.ai.provider.ProviderConfig;

/**
 * Ollama — OpenAI Chat Completions compatible.
 */
public final class OllamaProvider extends OpenAiCompatibleProvider {

    @Override
    protected ProviderConfig config() {
        return ProviderConfig.single(
            "ollama", "Ollama", "http://localhost:11434/v1",
            null, Protocol.OPENAI_COMPLETIONS,
            ModelData.ollamaModels());
    }
}
