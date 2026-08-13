package com.pijava.coding.agent.cli;

import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevel;

/**
 * Maps the {@code --thinking} CLI string to {@link ModelThinkingLevel}
 * (Phase 3 design §9.3).
 *
 * <p>pi's {@code "max"} level is merged into pi-java's {@code XHigh}
 * (label {@code "xhigh"}). Unknown values fall back to {@code Off} and are
 * reported as parse warnings by {@link ArgsParser}.</p>
 */
public final class ThinkingLevels {

    private ThinkingLevels() {}

    /**
     * Parse a raw {@code --thinking} value.
     *
     * @param raw value or null (treated as "off")
     * @return the mapped thinking level (unknown values fall back to off)
     */
    public static ModelThinkingLevel parse(String raw) {
        return switch (raw == null ? "off" : raw.toLowerCase()) {
            case "off" -> ModelThinkingLevel.off();
            case "minimal" -> ModelThinkingLevel.of(new ThinkingLevel.Minimal());
            case "low" -> ModelThinkingLevel.of(new ThinkingLevel.Low());
            case "medium" -> ModelThinkingLevel.of(new ThinkingLevel.Medium());
            case "high" -> ModelThinkingLevel.of(new ThinkingLevel.High());
            case "xhigh", "max" -> ModelThinkingLevel.of(new ThinkingLevel.XHigh());
            default -> ModelThinkingLevel.off();
        };
    }

    /** All accepted raw values, for validation and help text. */
    public static java.util.List<String> validValues() {
        return java.util.List.of(
            "off", "minimal", "low", "medium", "high", "xhigh", "max");
    }

    /** True when the raw value is a valid thinking level. */
    public static boolean isValid(String raw) {
        return raw != null && validValues().contains(raw.toLowerCase());
    }

    /**
     * Extract a thinking level from a model pattern's {@code ":thinking"}
     * suffix (e.g. {@code "claude-sonnet:high"}), or {@code null} when the
     * pattern has no valid suffix.
     */
    public static ModelThinkingLevel parseFromModelPattern(String pattern) {
        if (pattern == null) {
            return null;
        }
        var colon = pattern.lastIndexOf(':');
        if (colon <= 0) {
            return null;
        }
        var suffix = pattern.substring(colon + 1);
        return isValid(suffix) ? parse(suffix) : null;
    }
}
