package com.pijava.evals.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pijava.agent.harness.AgentHarness;
import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ChatApi;
import com.pijava.ai.provider.Provider;

/**
 * Simple {@link EvalContext} wrapping a provider and its ChatApi.
 *
 * @param provider the provider under test
 * @param chatApi  cached ChatApi
 * @param harness  optional harness (may be {@code null})
 * @param json     JSON mapper
 */
public record DefaultEvalContext(
    Provider provider,
    ChatApi chatApi,
    AgentHarness harness,
    ObjectMapper json
) implements EvalContext {

    /**
     * Create a context from a provider using default API options.
     *
     * @param provider provider under test
     * @return context with a ChatApi and a fresh ObjectMapper
     */
    public static DefaultEvalContext of(Provider provider) {
        return new DefaultEvalContext(
            provider,
            provider.createApi(ChatApi.class, ApiOptions.defaults()),
            null,
            new ObjectMapper());
    }
}
