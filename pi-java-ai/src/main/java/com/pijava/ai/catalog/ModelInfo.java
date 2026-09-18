package com.pijava.ai.catalog;

import java.util.Map;
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
 * @param headers           extra HTTP headers sent with requests for this model
 *                          (pi {@code Model.headers}; default empty)
 * @param samplingParams    arbitrary sampling params merged into the request body
 *                          (pi {@code Model.samplingParams}; default empty)
 * @param compat            provider compatibility flags (pi {@code Model.compat};
 *                          default {@link ModelCompat#NONE})
 */
public record ModelInfo(
    ModelId<?> id,
    String displayName,
    Set<ModelCapability> capabilities,
    int maxInputTokens,
    int maxOutputTokens,
    boolean deprecated,
    PricingInfo pricing,
    ThinkingLevelMap thinkingLevelMap,
    Map<String, String> headers,
    Map<String, Object> samplingParams,
    ModelCompat compat
) {
    /** Compact constructor that defensively copies capabilities and defaults a null thinking map. */
    public ModelInfo {
        capabilities = Set.copyOf(capabilities);
        if (thinkingLevelMap == null) {
            thinkingLevelMap = ThinkingLevelMap.empty();
        }
        headers = Map.copyOf(headers);
        samplingParams = Map.copyOf(samplingParams);
        if (compat == null) {
            compat = ModelCompat.NONE;
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
             deprecated, pricing, ThinkingLevelMap.empty(), Map.of(), Map.of());
    }

    /** Convenience constructor with thinking map but no headers/sampling params. */
    public ModelInfo(
        ModelId<?> id,
        String displayName,
        Set<ModelCapability> capabilities,
        int maxInputTokens,
        int maxOutputTokens,
        boolean deprecated,
        PricingInfo pricing,
        ThinkingLevelMap thinkingLevelMap
    ) {
        this(id, displayName, capabilities, maxInputTokens, maxOutputTokens,
             deprecated, pricing, thinkingLevelMap, Map.of(), Map.of());
    }

    /** Convenience constructor with headers/sampling params (empty thinking map). */
    public ModelInfo(
        ModelId<?> id,
        String displayName,
        Set<ModelCapability> capabilities,
        int maxInputTokens,
        int maxOutputTokens,
        boolean deprecated,
        PricingInfo pricing,
        Map<String, String> headers,
        Map<String, Object> samplingParams
    ) {
        this(id, displayName, capabilities, maxInputTokens, maxOutputTokens,
             deprecated, pricing, ThinkingLevelMap.empty(), headers, samplingParams);
    }

    /** Convenience constructor with headers/sampling params but no compat flags. */
    public ModelInfo(
        ModelId<?> id,
        String displayName,
        Set<ModelCapability> capabilities,
        int maxInputTokens,
        int maxOutputTokens,
        boolean deprecated,
        PricingInfo pricing,
        ThinkingLevelMap thinkingLevelMap,
        Map<String, String> headers,
        Map<String, Object> samplingParams
    ) {
        this(id, displayName, capabilities, maxInputTokens, maxOutputTokens,
             deprecated, pricing, thinkingLevelMap, headers, samplingParams, ModelCompat.NONE);
    }

    /**
     * The least a {@link ModelInfo} can be: identity only, everything else empty.
     *
     * <p>Used when a request names a model the catalog does not know (docs/31 §8.34.4 决策 5) —
     * today any {@link ModelId} is accepted, and that tolerance is preserved by synthesizing
     * rather than failing.</p>
     *
     * <p>⚠️ The synthesized {@code capabilities} is **empty**, which is indistinguishable from
     * "this model really does not support images". Any consumer that reads capabilities to
     * *downgrade* content (pi's unsupported-image placeholder, {@code transform-messages.ts:35-57})
     * must therefore keep "unknown" and "unsupported" apart, or it will mangle requests for
     * catalog misses.</p>
     */
    public static ModelInfo minimal(ModelId<?> id) {
        return new ModelInfo(id, id.modelName(), Set.of(), 0, 0, false,
            PricingInfo.UNKNOWN, ThinkingLevelMap.empty(), Map.of(), Map.of(), ModelCompat.NONE);
    }
}
