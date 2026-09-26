package com.pijava.ai.catalog;

import java.util.Locale;

/**
 * 哪个请求字段承载 max tokens（pi {@code OpenAICompletionsCompat.maxTokensField}，
 * {@code types.ts:685-686}）。
 *
 * <p>pi 的值是字符串联合 {@code "max_completion_tokens" | "max_tokens"}；本仓按
 * {@code CLAUDE.md} 的约定落成枚举（两个取值的纯常量闭集），{@link #wireName()} 给出线格
 * 字面量 —— 与 {@link com.pijava.ai.provider.Protocol} 同一手法。</p>
 *
 * <p>⚠️ <b>它不是「模型属性」而是「端点属性」</b>：pi 的探测
 * （{@code openai-completions.ts:1583} 的 {@code useMaxTokens}）按 provider/baseUrl 判，
 * 判据里没有模型本身。因此 {@link ModelCompat#maxTokensField()} 的三态里 {@code null}
 * 是**常态**（「照端点探测」），只有用户显式写 models.json 才会非空。</p>
 *
 * @see com.pijava.ai.catalog.CompatResolver
 */
public enum MaxTokensField {
    /** pi {@code "max_tokens"} —— {@code detectCompat:1641} 在 {@code useMaxTokens} 为真时选它。 */
    MAX_TOKENS,

    /** pi {@code "max_completion_tokens"} —— OpenAI 自己的字段名，也是探测的缺省。 */
    MAX_COMPLETION_TOKENS;

    /** 线格字面量（pi 的那个字符串）。 */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
