package com.pijava.ai.catalog;

import java.util.Set;

import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;

/**
 * Metadata about a specific model.
 *
 * @param id           the model identifier
 * @param displayName  human-readable name
 * @param capabilities the features this model supports
 * @param maxInputTokens   maximum context window size in tokens
 * @param maxOutputTokens  maximum output tokens per request
 * @param deprecated   {@code true} if the model is scheduled for removal
 * @param pricing      input/output price per million tokens, or {@link PricingInfo#UNKNOWN}
 */
public record ModelInfo(
    ModelId<?> id,
    String displayName,
    Set<ModelCapability> capabilities,
    int maxInputTokens,
    int maxOutputTokens,
    boolean deprecated,
    PricingInfo pricing
) {
    public ModelInfo {
        capabilities = Set.copyOf(capabilities);
    }
}
