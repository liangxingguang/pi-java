package com.pijava.ai.provider.builtin;

import com.pijava.ai.provider.AnthropicCompatibleProvider;
import com.pijava.ai.provider.Protocol;
import com.pijava.ai.provider.ProviderConfig;

/**
 * MiniMax — Anthropic Messages compatible.
 */
public final class MiniMaxProvider extends AnthropicCompatibleProvider {

    @Override
    protected ProviderConfig config() {
        return ProviderConfig.single(
            "minimax", "MiniMax", "https://api.minimax.io/anthropic",
            "MINIMAX_API_KEY", Protocol.ANTHROPIC_MESSAGES,
            ModelData.miniMaxModels());
    }
}
