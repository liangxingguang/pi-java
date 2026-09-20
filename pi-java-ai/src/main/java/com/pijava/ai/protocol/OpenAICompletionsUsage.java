package com.pijava.ai.protocol;

import java.util.LinkedHashMap;
import java.util.Map;

import com.openai.core.JsonValue;
import com.openai.models.completions.CompletionUsage;

import com.pijava.ai.Usage;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.model.CostCalculator;

/**
 * OpenAI-completions 车道的 usage 归一 —— pi {@code openai-completions.ts:1509-1550}
 * {@code parseChunkUsage} 的逐条移植（包 H1 步 4，{@code docs/42 §2.1 P8–P10}）。
 *
 * <p>三条语义必须逐字照抄，否则会得到与 pi <b>不同的数</b>：</p>
 *
 * <ol>
 *   <li><b>cacheRead 是三路 {@code ??} 链</b>（{@code :1521-1522}）：
 *       {@code prompt_tokens_details.cached_tokens} → {@code prompt_cache_hit_tokens}
 *       → 顶层 {@code cached_tokens} → 0。用 {@code ??} <b>不是</b> {@code ||}
 *       ⇒ <b>显式存在的 {@code 0} 会短路后续来源</b>。三个来源对应三家 provider
 *       （OpenAI/OpenRouter · DeepSeek · Kimi），注释逐字点名。</li>
 *   <li><b>减法</b>（{@code :1536}）：{@code input = Math.max(0, prompt_tokens − cacheRead − cacheWrite)}。
 *       注释逐字：<i>"Do not subtract writes from cached_tokens, otherwise spec-compliant
 *       providers are under-reported."</i> ⇒ cacheWrite 从 {@code prompt_tokens} 里减，
 *       但<b>不从 {@code cached_tokens} 里减</b>。</li>
 *   <li><b>{@code reasoning} 用 {@code || 0}</b>（{@code :1544}）⇒ 本车道它<b>恒为数字</b>，
 *       与 Anthropic 车道的「不报就 null」相反。</li>
 * </ol>
 *
 * <p>{@code totalTokens} <b>自算四分量和</b>（{@code :1545}），不用 provider 的
 * {@code total_tokens} —— 与 responses 车道相反。</p>
 *
 * <p>⚠️ SDK 面：{@code PromptTokensDetails} <b>没有</b> {@code cache_write_tokens} 访问器
 * （只有 audio/cached），且 {@code prompt_cache_hit_tokens}/顶层 {@code cached_tokens}
 * 是 provider 扩展键 —— 三者都从 {@code _additionalProperties()} 的原始 JSON 里取。</p>
 */
final class OpenAICompletionsUsage {

    private OpenAICompletionsUsage() {
    }

    /** typed 入口（顶层 {@code chunk.usage}）。 */
    static Usage parse(CompletionUsage raw, ModelInfo model) {
        return parse(rawOf(raw), model);
    }

    /**
     * 未类型化入口 —— {@code choice.usage} 回退走这条（pi 的 {@code (choice as any).usage}）。
     *
     * @param raw   pi 键名的原始视图
     * @param model 计价用模型；{@code null} ⇒ 不计价
     * @return 归一后的 usage
     */
    static Usage parse(Map<String, Object> raw, ModelInfo model) {
        double promptTokens = number(raw.get("prompt_tokens"));
        // `??` 链：null 才算缺席，显式的 0 短路后续来源
        double cacheReadTokens = firstPresent(
            nested(raw, "prompt_tokens_details", "cached_tokens"),
            raw.get("prompt_cache_hit_tokens"),
            raw.get("cached_tokens"));
        double cacheWriteTokens = number(nested(raw, "prompt_tokens_details", "cache_write_tokens"));

        double input = Math.max(0, promptTokens - cacheReadTokens - cacheWriteTokens);
        double outputTokens = number(raw.get("completion_tokens"));
        double reasoning = number(nested(raw, "completion_tokens_details", "reasoning_tokens"));
        double totalTokens = input + outputTokens + cacheReadTokens + cacheWriteTokens;

        var usage = new Usage(input, outputTokens, cacheReadTokens, cacheWriteTokens,
            null, reasoning, totalTokens, Usage.Cost.zero());
        return model == null
            ? usage
            : usage.withCost(CostCalculator.calculateCost(model.pricing(), usage));
    }

    /**
     * 从 choice 的扩展键里取 {@code usage}（pi 的 {@code (choice as any).usage}）。
     *
     * @param additionalProperties choice 的 {@code _additionalProperties()}
     * @return pi 键名的原始视图，缺席或形状不对时 {@code null}
     */
    static Map<String, Object> choiceUsage(Map<String, JsonValue> additionalProperties) {
        var value = additionalProperties.get("usage");
        if (value == null) {
            return null;
        }
        try {
            if (value.convert(Object.class) instanceof Map<?, ?> map) {
                var out = new LinkedHashMap<String, Object>();
                map.forEach((k, v) -> out.put(String.valueOf(k), v));
                return out;
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    // ── typed → pi 键名的原始视图 ────────────────────────────────────────

    private static Map<String, Object> rawOf(CompletionUsage usage) {
        var raw = new LinkedHashMap<String, Object>();
        usage._promptTokens().asKnown().ifPresent(v -> raw.put("prompt_tokens", v));
        usage._completionTokens().asKnown().ifPresent(v -> raw.put("completion_tokens", v));
        usage._promptTokensDetails().asKnown().ifPresent(details -> {
            var out = new LinkedHashMap<String, Object>();
            details._cachedTokens().asKnown().ifPresent(v -> out.put("cached_tokens", v));
            details._cacheWriteTokens().asKnown().ifPresent(v -> out.put("cache_write_tokens", v));
            details._additionalProperties().forEach((k, v) -> out.put(k, unwrap(v)));
            raw.put("prompt_tokens_details", out);
        });
        usage._completionTokensDetails().asKnown().ifPresent(details -> {
            var out = new LinkedHashMap<String, Object>();
            details._reasoningTokens().asKnown().ifPresent(v -> out.put("reasoning_tokens", v));
            raw.put("completion_tokens_details", out);
        });
        usage._additionalProperties().forEach((k, v) -> raw.put(k, unwrap(v)));
        return raw;
    }

    /**
     * 把扩展键的 {@link JsonValue} 还原成普通 Java 值。
     *
     * <p>⚠️ {@code JsonValue} 继承的是<b>裸</b> {@code JsonField} ⇒ 其访问器在编译期被擦除
     * （{@code asObject().get()} 静态类型是 {@code Object}）。这里只还原标量 —— 本车道需要的
     * 扩展键（顶层 {@code cached_tokens} / {@code prompt_cache_hit_tokens}）都是标量；
     * 嵌套的 {@code prompt_tokens_details.*} 走类型化访问器，不走这条路。</p>
     */
    private static Object unwrap(JsonValue value) {
        try {
            var number = value.asNumber();
            if (number.isPresent()) {
                return number.get();
            }
            var string = value.asString();
            if (string.isPresent()) {
                return string.get();
            }
            var bool = value.asBoolean();
            if (bool.isPresent()) {
                return bool.get();
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    // ── 取值 ────────────────────────────────────────────────────────────

    private static Object nested(Map<String, Object> raw, String outer, String inner) {
        return raw.get(outer) instanceof Map<?, ?> map ? map.get(inner) : null;
    }

    private static double firstPresent(Object... candidates) {
        for (var candidate : candidates) {
            if (candidate != null) {
                return number(candidate);
            }
        }
        return 0;
    }

    private static double number(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0;
    }
}
