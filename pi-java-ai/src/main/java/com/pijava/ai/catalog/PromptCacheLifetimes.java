package com.pijava.ai.catalog;

import java.util.Map;

/**
 * pi {@code ModelPromptCache}（{@code model-schema.ts:15-22}）：两档缓存寿命秒数。
 *
 * <p>pi schema 是 {@code Partial<Record<"short"|"long", number>>}，{@code exclusiveMinimum:0}；
 * 组件 {@code null} ≙ 该档未知（不 warm），与「键缺席」同形。消费链（CacheWarmer）
 * 不在批 3a 范围，本类型只承载数据。</p>
 *
 * @param shortSeconds best-effort 5-minute cache lifetime in seconds, or {@code null}
 * @param longSeconds  best-effort 1-hour cache lifetime in seconds, or {@code null}
 */
public record PromptCacheLifetimes(Integer shortSeconds, Integer longSeconds) {

    /**
     * 边界转换：只认 {@code short}/{@code long} 两键（其余忽略）；{@code null}/≤0 的档
     * 归一为 {@code null}（宽松加载不炸 models.json，≙ pi schema 拒载的安全方向）；
     * 两档皆无 ⇒ {@code null}（不造空壳）。
     */
    public static PromptCacheLifetimes fromWire(Map<String, Double> wire) {
        if (wire == null || wire.isEmpty()) {
            return null;
        }
        var shortSeconds = lifetime(wire.get("short"));
        var longSeconds = lifetime(wire.get("long"));
        if (shortSeconds == null && longSeconds == null) {
            return null;
        }
        return new PromptCacheLifetimes(shortSeconds, longSeconds);
    }

    /** 两表浅合并（pi {@code applyModelOverride:196} 键级 {@code {...base, ...override}}）。 */
    public static PromptCacheLifetimes merge(PromptCacheLifetimes base,
                                              PromptCacheLifetimes override) {
        if (override == null) {
            return base;
        }
        if (base == null) {
            return override;
        }
        return new PromptCacheLifetimes(
            override.shortSeconds() != null ? override.shortSeconds() : base.shortSeconds(),
            override.longSeconds() != null ? override.longSeconds() : base.longSeconds());
    }

    private static Integer lifetime(Double value) {
        if (value == null || value <= 0) {
            return null;
        }
        return value.intValue();
    }
}
