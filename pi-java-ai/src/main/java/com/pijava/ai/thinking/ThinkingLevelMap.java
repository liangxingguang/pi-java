package com.pijava.ai.thinking;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Per-model translation table: each supported {@link ThinkingLevel} maps to
 * a provider-specific {@link ThinkingConfig}.
 *
 * <p>Aligned with pi {@code Model.thinkingLevelMap}. Only includes levels
 * the model actually supports. {@link ModelThinkingLevel.Off} maps directly
 * to {@link ThinkingConfig#OFF}.</p>
 */
public record ThinkingLevelMap(
    Map<ThinkingLevel, ThinkingConfig> levelMap
) {
    public ThinkingLevelMap {
        levelMap = Map.copyOf(levelMap);
    }

    /**
     * Translate a {@link ModelThinkingLevel} into the provider-specific
     * {@link ThinkingConfig} for this model.
     */
    public ThinkingConfig forLevel(ModelThinkingLevel level) {
        return switch (level) {
            case ModelThinkingLevel.Off o  -> ThinkingConfig.OFF;
            case ModelThinkingLevel.Enabled e -> {
                var clamped = ThinkingLevel.clamp(e.level(), levelMap.keySet());
                yield levelMap.getOrDefault(clamped, ThinkingConfig.OFF);
            }
        };
    }

    /** Return the set of supported {@link ThinkingLevel}s for this model. */
    public Set<ThinkingLevel> supportedLevels() {
        return levelMap.keySet();
    }

    /** Create an empty map (model doesn't support extended thinking). */
    public static ThinkingLevelMap empty() {
        return new ThinkingLevelMap(Map.of());
    }

    /** Create from individual level→config entries. */
    public static ThinkingLevelMap of(Map<ThinkingLevel, ThinkingConfig> map) {
        return new ThinkingLevelMap(map);
    }
}
