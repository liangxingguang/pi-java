package com.pijava.ai.api;

import java.time.Duration;
import java.util.Map;

/**
 * Common options for LLM API calls.
 *
 * @param baseUrl       override the default API base URL (or empty for default)
 * @param apiKey        the API key (or empty to resolve from the environment)
 * @param timeout       HTTP request timeout
 * @param maxRetries    maximum number of retries on transient failures
 * @param extra         provider-specific options
 */
public record ApiOptions(
    String baseUrl,
    String apiKey,
    Duration timeout,
    int maxRetries,
    Map<String, Object> extra
) {
    public ApiOptions {
        extra = Map.copyOf(extra);
    }

    /** Reasonable defaults for interactive use. */
    public static ApiOptions defaults() {
        return new ApiOptions("", "", Duration.ofSeconds(120), 2, Map.of());
    }
}
