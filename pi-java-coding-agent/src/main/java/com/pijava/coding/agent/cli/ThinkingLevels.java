package com.pijava.coding.agent.cli;

import com.pijava.ai.thinking.ModelThinkingLevel;

/**
 * Maps the {@code --thinking} CLI string to {@link ModelThinkingLevel}
 * (Phase 3 design §9.3).
 *
 * <p>pi 的字面量：{@code "off" | "minimal" | "low" | "medium" | "high" | "xhigh" | "max"}。
 * Unknown values fall back to {@code Off} and are reported as parse warnings by
 * {@link ArgsParser}.</p>
 *
 * <p>⚠️ <b>包H5 步8</b>：此前 pi-java 只有 5 级、把 {@code "max"} <b>并进</b>
 * {@code XHigh}；pi 的 {@code ThinkingLevel} 是 6 级（{@code types.ts:84}），
 * 故改为委托 {@link ModelThinkingLevel#parse}。</p>
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
        return ModelThinkingLevel.parse(raw).orElseGet(ModelThinkingLevel::off);
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
