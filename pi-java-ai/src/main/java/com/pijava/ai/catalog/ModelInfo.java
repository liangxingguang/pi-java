package com.pijava.ai.catalog;

import java.util.Set;

import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.thinking.ThinkingLevelMap;

/**
 * Metadata about a specific model.
 *
 * @param id                the model identifier
 * @param displayName       human-readable name
 * @param capabilities      the features this model supports
 * @param maxInputTokens    maximum context window size in tokens
 * @param maxOutputTokens   maximum output tokens per request
 * @param deprecated        {@code true} if the model is scheduled for removal
 * @param pricing           input/output price per million tokens, or {@link PricingInfo#UNKNOWN}
 * @param thinkingLevelMap  per-model translation from ThinkingLevel → provider config
 */
public record ModelInfo(
    ModelId<?> id,
    String displayName,
    Set<ModelCapability> capabilities,
    int maxInputTokens,
    int maxOutputTokens,
    boolean deprecated,
    PricingInfo pricing,
    ThinkingLevelMap thinkingLevelMap
) {
    /** Compact constructor that defensively copies capabilities and defaults a null thinking map. */
    public ModelInfo {
        capabilities = Set.copyOf(capabilities);
        if (thinkingLevelMap == null) {
            thinkingLevelMap = ThinkingLevelMap.empty();
        }
    }

    /** Convenience constructor for models without thinking support. */
    public ModelInfo(
        ModelId<?> id,
        String displayName,
        Set<ModelCapability> capabilities,
        int maxInputTokens,
        int maxOutputTokens,
        boolean deprecated,
        PricingInfo pricing
    ) {
        this(id, displayName, capabilities, maxInputTokens, maxOutputTokens,
             deprecated, pricing, ThinkingLevelMap.empty());
    }
}
