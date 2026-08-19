package com.pijava.ai.provider;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ChatApi;
import com.pijava.ai.catalog.BuiltinCatalog;
import com.pijava.ai.protocol.GoogleGenerativeAiApi;

/**
 * Google provider — Gemini models via the Generative AI API.
 */
public final class GoogleProvider extends ConfigurableProvider {

    @Override
    protected ProviderConfig config() {
        return ProviderConfig.single(
            "google", "Google", "https://generativelanguage.googleapis.com",
            "GEMINI_API_KEY", Protocol.GOOGLE_GENERATIVE_AI,
            BuiltinCatalog.googleModels());
    }

    @Override
    protected ChatApi createChatApi(Protocol protocol, ApiOptions options) {
        return switch (protocol) {
            case GOOGLE_GENERATIVE_AI -> new GoogleGenerativeAiApi(options);
            default -> throw new IllegalArgumentException(
                "GoogleProvider cannot serve protocol " + protocol);
        };
    }
}
