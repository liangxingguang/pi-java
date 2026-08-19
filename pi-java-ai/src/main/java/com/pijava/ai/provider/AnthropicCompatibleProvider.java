package com.pijava.ai.provider;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ChatApi;
import com.pijava.ai.protocol.AnthropicMessagesApi;

/**
 * Anthropic-compatible Provider base class.
 *
 * <p>Providers that expose an Anthropic Messages-compatible endpoint
 * (e.g. MiniMax) use this base class, which constructs an
 * {@link AnthropicMessagesApi} with the provider's specific baseUrl
 * and API key env var.</p>
 */
public abstract class AnthropicCompatibleProvider extends ConfigurableProvider {

    @Override
    protected ChatApi createChatApi(Protocol protocol, ApiOptions options) {
        return switch (protocol) {
            case ANTHROPIC_MESSAGES ->
                new AnthropicMessagesApi(options, config().apiKeyEnvVar());
            default -> throw new IllegalArgumentException(
                "AnthropicCompatibleProvider cannot serve protocol " + protocol);
        };
    }
}
