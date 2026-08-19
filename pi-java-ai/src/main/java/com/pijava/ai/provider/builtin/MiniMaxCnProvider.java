package com.pijava.ai.provider.builtin;

import com.pijava.ai.provider.AnthropicCompatibleProvider;
import com.pijava.ai.provider.Protocol;
import com.pijava.ai.provider.ProviderConfig;

/**
 * MiniMax CN — Anthropic Messages compatible.
 */
public final class MiniMaxCnProvider extends AnthropicCompatibleProvider {

    @Override
    protected ProviderConfig config() {
        return ProviderConfig.single(
            "minimax-cn", "MiniMax CN", "https://api.minimaxi.com/anthropic",
            "MINIMAX_CN_API_KEY", Protocol.ANTHROPIC_MESSAGES,
            ModelData.miniMaxCnModels());
    }
}
