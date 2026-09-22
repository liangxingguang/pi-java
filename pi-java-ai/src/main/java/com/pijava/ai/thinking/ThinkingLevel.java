package com.pijava.ai.thinking;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 思考深度 —— <b>6 级</b>刻度（pi {@code ThinkingLevel}，{@code types.ts:84}）。
 *
 * <p>pi 的字面量：{@code "minimal" | "low" | "medium" | "high" | "xhigh" | "max"}。</p>
 *
 * <p>⚠️ <b>包H5 的改动</b>：pi-java 此前只有 5 级 —— 把 pi 的 {@code "max"} 并进了
 * {@link XHigh}（{@code cli/ThinkingLevels.java:31} 的 {@code case "xhigh", "max" ->}）。
 * 这与 pi 的刻度不同（pi 的 {@code clampThinkingLevel} 与 {@code thinkingLevelMap}
 * 都按 6 级走），故拆开。</p>
 *
 * <p>{@code "off"} <b>不是</b> {@code ThinkingLevel} —— 它是 {@link ModelThinkingLevel.Off}。</p>
 */
public sealed interface ThinkingLevel {
    record Minimal() implements ThinkingLevel {}
    record Low() implements ThinkingLevel {}
    record Medium() implements ThinkingLevel {}
    record High() implements ThinkingLevel {}
    record XHigh() implements ThinkingLevel {}
    record Max() implements ThinkingLevel {}

    /**
     * pi 字面量（{@code "minimal"} … {@code "max"}）。
     *
     * <p>逐字对齐 pi：{@code XHigh} ⇒ {@code "xhigh"}、{@code Max} ⇒ {@code "max"}
     * —— 二者<b>不再合并</b>。</p>
     */
    default String label() {
        return getClass().getSimpleName().toLowerCase(Locale.ROOT);
    }

    /** 6 级，顺序与 pi 的 {@code EXTENDED_THINKING_LEVELS}（去掉 {@code off}）一致。 */
    static List<ThinkingLevel> ordered() {
        return List.of(new Minimal(), new Low(), new Medium(),
                       new High(), new XHigh(), new Max());
    }

    /** pi 字面量 ⇒ 级别。未知值（含 {@code "off"}）返回空。 */
    static Optional<ThinkingLevel> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "minimal" -> Optional.of(new Minimal());
            case "low" -> Optional.of(new Low());
            case "medium" -> Optional.of(new Medium());
            case "high" -> Optional.of(new High());
            case "xhigh" -> Optional.of(new XHigh());
            case "max" -> Optional.of(new Max());
            default -> Optional.empty();
        };
    }
}
