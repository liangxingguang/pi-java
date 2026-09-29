package com.pijava.ai.catalog;

import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * pi {@code compat.cacheControlFormat}（{@code types.ts:737}，
 * {@code openai-completions.ts:1632}/{@code :1073}）：completions 线上
 * {@code cache_control} 的线格形状 —— 单值闭集 {@code "anthropic"}。
 *
 * <p>语义：该模型虽然走 openai-completions 车道，但端点接受 <b>anthropic 形状</b>的
 * 缓存断点（{@code {type:"ephemeral",ttl?}} 挂在文本分片/工具末项上）。今天唯一的
 * 生产消费者是 OpenRouter 的 {@code anthropic/*}（含 {@code :batch}）一族
 * —— 探测判据 {@code provider === "openrouter" && id.startsWith("anthropic/")}
 * 是**严格 provider 等值**（baseUrl 命中 openrouter.ai 的中转站不算，
 * {@code detectCompat:1632} 与 {@code :1595} 刻意不同）。</p>
 *
 * <p>纯常量闭集 ⇒ enum（CLAUDE.md 规范，与 {@link MaxTokensField} 同形）。</p>
 */
public enum CacheControlFormat {
    /** pi 的 {@code "anthropic"}。 */
    ANTHROPIC;

    /** pi 线格字面量。 */
    @JsonValue
    public String wireName() {
        return "anthropic";
    }

    /**
     * 精确匹配（pi 的 zod 是 {@code Type.Literal("anthropic")}）。
     *
     * @param raw 线格值
     * @return 命中；未知取值返回空（调用方决定响亮抛错）
     */
    public static Optional<CacheControlFormat> parse(String raw) {
        return "anthropic".equals(raw) ? Optional.of(ANTHROPIC) : Optional.empty();
    }
}
