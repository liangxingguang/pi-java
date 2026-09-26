package com.pijava.ai.catalog;

/**
 * 一条 Anthropic 缓存断点的**规格** —— pi {@code getCacheControl} 返回值里
 * {@code cacheControl} 那一半的纯值投影（{@code anthropic-messages.ts:69-83}）。
 *
 * <p><b>为什么是纯值</b>：pi 的 {@code CacheControlEphemeral} 是厂商 SDK 的类型，而
 * {@link CompatResolver} 在 {@code catalog} 包 —— 解析层不得把 {@code com.anthropic.*}
 * 泄漏进来（换 SDK、或非 Anthropic 车道复用同一解析时都会付代价）。车道的活是把本记录
 * 翻成 {@code CacheControlEphemeral}（{@code docs/54 §4.2}）。</p>
 *
 * <p><b>只有 ttl 一位</b>：pi 的 {@code cacheControl} 常量部分是 {@code {type:"ephemeral"}}
 * （无条件的），唯一变量是长缓存的 {@code ttl:"1h"}。断点「发不发」由
 * {@link CompatResolver#anthropicCacheControl} 的 {@code Optional} 承载（{@code none}
 * 缺席），落到哪个线上位置由车道决定。</p>
 *
 * @param oneHourTtl 是否携带 {@code ttl:"1h"} —— 只由 {@code cacheRetention:"long"}
 *                   **且** {@code compat.supportsLongCacheRetention}（缺省 {@code true}）
 *                   共同决定（{@code anthropic-messages.ts:79-82}）
 */
public record CacheBreakpointSpec(boolean oneHourTtl) {
}
