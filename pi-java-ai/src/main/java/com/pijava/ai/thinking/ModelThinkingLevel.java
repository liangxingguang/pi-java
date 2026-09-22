package com.pijava.ai.thinking;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 模型思考模式 —— {@code off} 或某个具体 {@link ThinkingLevel}
 * （pi {@code ModelThinkingLevel = "off" | ThinkingLevel}，{@code types.ts:85}）。
 *
 * <p>两层设计：{@link Off} 表示完全关闭；{@link Enabled} 携带具体级别。</p>
 */
public sealed interface ModelThinkingLevel {
    record Off() implements ModelThinkingLevel {}
    record Enabled(ThinkingLevel level) implements ModelThinkingLevel {}

    static ModelThinkingLevel off() { return new Off(); }
    static ModelThinkingLevel of(ThinkingLevel level) { return new Enabled(level); }

    /**
     * pi 的 {@code EXTENDED_THINKING_LEVELS}（{@code models.ts:922}）—— <b>含 {@code off}</b>，
     * 共 7 项，顺序逐字照抄：{@code off, minimal, low, medium, high, xhigh, max}。
     *
     * <p>它是 {@code getSupportedThinkingLevels} 与 {@code clampThinkingLevel} 的<b>刻度</b>
     * （那两个函数在 {@link com.pijava.ai.catalog.ModelThinkingLevels}）。</p>
     */
    static List<ModelThinkingLevel> extended() {
        var levels = new ArrayList<ModelThinkingLevel>();
        levels.add(off());
        for (var level : ThinkingLevel.ordered()) {
            levels.add(of(level));
        }
        return List.copyOf(levels);
    }

    /** pi 字面量（{@code "off"} 或某个级别名）。未知值返回空。 */
    static Optional<ModelThinkingLevel> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        if ("off".equalsIgnoreCase(raw)) {
            return Optional.of(off());
        }
        return ThinkingLevel.parse(raw).map(ModelThinkingLevel::of);
    }

    /** pi 字面量：{@code "off"} 或级别名。 */
    default String label() {
        return switch (this) {
            case ModelThinkingLevel.Off o -> "off";
            case ModelThinkingLevel.Enabled e -> e.level().label();
        };
    }
}
