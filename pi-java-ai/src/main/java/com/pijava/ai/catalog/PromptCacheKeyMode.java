package com.pijava.ai.catalog;

import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * pi {@code compat.promptCacheKeyMode}（{@code compat-schema.ts:192-194}，
 * v1.6.0 新增）：{@code prompt_cache_key} 请求字段的发送策略 ——
 * {@code "auto" | "enabled" | "disabled"}。
 *
 * <p>语义（pi 的 schema 描述与两车道的读点）：</p>
 * <ul>
 *   <li>{@link #AUTO}（缺省）：completions 车道只在 {@code api.openai.com} 或
 *       {@code cacheRetention:"long"} ＋ {@code supportsLongCacheRetention} 时发；
 *       responses 车道与缓存一起发。</li>
 *   <li>{@link #ENABLED}：只要有缓存（{@code cacheRetention !== "none"}）就发 ——
 *       给接受该键的兼容代理用。</li>
 *   <li>{@link #DISABLED}：永不发 —— 给拒绝该键的端点用。</li>
 * </ul>
 *
 * <p>纯常量闭集 ⇒ enum（CLAUDE.md 规范，与 {@link SessionAffinityFormat} 同形）。</p>
 */
public enum PromptCacheKeyMode {
    AUTO,
    ENABLED,
    DISABLED;

    /** pi 的线格字面量。 */
    @JsonValue
    public String wireName() {
        return switch (this) {
            case AUTO -> "auto";
            case ENABLED -> "enabled";
            case DISABLED -> "disabled";
        };
    }

    /**
     * 精确匹配（pi 的 zod 联合只收三个字面量）。
     *
     * @param raw 线格值
     * @return 命中；未知取值返回空（调用方决定响亮抛错）
     */
    public static Optional<PromptCacheKeyMode> parse(String raw) {
        return switch (raw) {
            case "auto" -> Optional.of(AUTO);
            case "enabled" -> Optional.of(ENABLED);
            case "disabled" -> Optional.of(DISABLED);
            default -> Optional.empty();
        };
    }
}
