package com.pijava.ai.protocol;

import java.util.Locale;
import java.util.Map;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.thinking.ThinkingLevel;

/**
 * OpenAI Responses 协议专属选项（对齐 pi {@code OpenAIResponsesStreamOptions}）。
 *
 * <p>从 {@link ApiOptions#extra()} 读取，键名与 pi 对齐：{@code reasoningEffort} /
 * {@code reasoningSummary} / {@code serviceTier} / {@code cacheRetention} /
 * {@code sessionId}。</p>
 */
public record ResponsesOptions(
    /** 推理强度；为 null 时不发送 reasoning 配置 */
    ThinkingLevel reasoningEffort,
    /** pi: reasoningSummary "auto"|"detailed"|"concise"|null */
    String reasoningSummary,
    /** pi: serviceTier */
    String serviceTier,
    /** pi: cacheRetention "short"（默认）|"long"|"none" */
    CacheRetention cacheRetention,
    /** pi: sessionId —— 用于 prompt cache key / 会话亲和 */
    String sessionId
) {
    /** pi: cacheRetention；long → prompt_cache_retention="24h" */
    public enum CacheRetention {
        SHORT, LONG, NONE;

        /** 解析 wire 值；未知或空回落到 SHORT（pi 默认）。 */
        public static CacheRetention parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return SHORT;
            }
            return switch (raw.toLowerCase(Locale.ROOT)) {
                case "long" -> LONG;
                case "none" -> NONE;
                default -> SHORT;
            };
        }
    }

    /** 从 {@link ApiOptions#extra()} 读取本协议选项。 */
    public static ResponsesOptions from(ApiOptions options) {
        Map<String, Object> extra = options.extra();
        Object effort = extra.get("reasoningEffort");
        return new ResponsesOptions(
            effort instanceof ThinkingLevel tl ? tl
                : effort instanceof String s ? parseLevel(s) : null,
            stringOrNull(extra.get("reasoningSummary")),
            stringOrNull(extra.get("serviceTier")),
            CacheRetention.parse(stringOrNull(extra.get("cacheRetention"))),
            stringOrNull(extra.get("sessionId")));
    }

    /** 解析 wire 值（"minimal"|"low"|"medium"|"high"|"xhigh"|"max"）为 ThinkingLevel。 */
    private static ThinkingLevel parseLevel(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "minimal" -> new ThinkingLevel.Minimal();
            case "low" -> new ThinkingLevel.Low();
            case "medium" -> new ThinkingLevel.Medium();
            case "high" -> new ThinkingLevel.High();
            case "xhigh", "max" -> new ThinkingLevel.XHigh();
            default -> null;
        };
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : value.toString();
    }
}
