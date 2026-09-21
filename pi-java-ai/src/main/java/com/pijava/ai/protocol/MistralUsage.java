package com.pijava.ai.protocol;

import java.util.Map;

import com.pijava.ai.Usage;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.model.CostCalculator;

/**
 * Mistral 车道的 usage 归一 —— pi {@code mistral-conversations.ts:536-555}
 * （{@code getMistralCachedPromptTokens}）与 {@code :596-611} 的逐条移植
 * （包 H1 步 5，{@code docs/42 §2.1 P14/P15}）。
 *
 * <p>四条语义必须逐字照抄，否则会得到与 pi <b>不同的数</b>：</p>
 *
 * <ol>
 *   <li><b>cacheRead 是六路 {@code ??} 链</b>（{@code :544-551}）：驼峰/下划线两种拼写 ×
 *       {@code …Details}/{@code …Detail} 两种容器名，再加两个顶层 {@code num*}。
 *       {@code ??} 不是 {@code ||} ⇒ <b>显式存在的 0 短路后续来源</b>。</li>
 *   <li><b>typeof + isFinite 门在链之后</b>（{@code :552}）：链命中一个字符串也算命中，
 *       门把它归 0 后<b>不</b>回落到下一来源（pi 的 {@code ??} 只认 null/undefined）。</li>
 *   <li><b>双重钳位</b>（{@code :553}）：{@code Math.min(promptTokens, Math.max(0, cached))}
 *       —— 下界 0、上界 promptTokens。</li>
 *   <li><b>{@code totalTokens} 优先 provider、兜底自算</b>（{@code :603-605}）：
 *       {@code total_tokens || (input + output + cacheRead + cacheWrite)}；
 *       而 {@code reasoning} <b>从不设置</b>（{@code :599} 的赋值列表里没有它）⇒
 *       保持 undefined ≙ {@code null}，与 completions/responses 车道的「{@code || 0}
 *       恒为数字」相反。</li>
 * </ol>
 */
final class MistralUsage {

    private MistralUsage() {
    }

    /**
     * @param raw   线格 {@code chunk.usage} 的原始视图（pi 键名）
     * @param model 计价用模型；{@code null} ⇒ 不计价
     * @return 归一后的 usage
     */
    static Usage parse(Map<String, Object> raw, ModelInfo model) {
        double promptTokens = number(raw.get("prompt_tokens"));
        // 六路 `??` 链（pi :544-551）：null 才算缺席，显式的 0 短路后续来源。
        // 注意 typeof/isFinite 门（:552）在链**之后** —— 命中非数字也算命中，归 0 不回落。
        double cached = number(firstPresent(
            nested(raw, "promptTokensDetails", "cachedTokens"),
            nested(raw, "prompt_tokens_details", "cached_tokens"),
            nested(raw, "promptTokenDetails", "cachedTokens"),
            nested(raw, "prompt_token_details", "cached_tokens"),
            raw.get("numCachedTokens"),
            raw.get("num_cached_tokens")));
        // 双重钳位（:553）：下界 0、上界 promptTokens
        double cacheRead = Math.min(promptTokens, Math.max(0, cached));
        // :599 —— 钳位已保证 cached ≤ prompt，这里的 max(0, …) 在 Mistral 是冗余的，
        // 照抄不删（pi 有，删了就成了无记录的偏差）。
        double input = Math.max(0, promptTokens - cacheRead);
        double output = number(raw.get("completion_tokens"));
        double providerTotal = number(raw.get("total_tokens"));
        // :603-605 —— `||`：provider 值为 falsy（缺席或 0）才自算四分量之和。
        double totalTokens = providerTotal != 0
            ? providerTotal
            : input + output + cacheRead;

        var usage = new Usage(input, output, cacheRead, 0, null, null,
            totalTokens, Usage.Cost.zero());
        return model == null
            ? usage
            : usage.withCost(CostCalculator.calculateCost(model.pricing(), usage));
    }

    // ── 取值 ────────────────────────────────────────────────────────────

    private static Object nested(Map<String, Object> raw, String outer, String inner) {
        return raw.get(outer) instanceof Map<?, ?> map ? map.get(inner) : null;
    }

    private static Object firstPresent(Object... candidates) {
        for (var candidate : candidates) {
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    /** pi 的 {@code typeof x === "number" && Number.isFinite(x) ? x : 0}。 */
    private static double number(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0;
    }
}
