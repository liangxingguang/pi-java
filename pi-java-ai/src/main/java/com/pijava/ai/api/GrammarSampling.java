package com.pijava.ai.api;

import java.util.Map;

/**
 * pi {@code {type:"grammar", variants}}（{@code types.ts:595-598}）：
 * provider 以 OpenAI custom tool 的 lark/regex 文法约束工具调用。
 *
 * <p>本形状只承载配置；能力门为 false 时静默回落 function tool、门开但无可用变体时
 * 才硬错 —— 解析见 {@code GrammarInputProperties}（docs/69）。</p>
 *
 * @param variants pi {@code GrammarVariants}：键 {@code openai_lark}/{@code openai_regex}，
 *                 值为文法定义。空表不抛：抛错只发生在 resolve 且能力门打开时（照 pi）
 */
public record GrammarSampling(Map<String, String> variants) implements ConstrainedSampling {

    /** Compact constructor: defensively copy; null/empty variants are allowed on the shape. */
    public GrammarSampling {
        variants = variants == null ? Map.of() : Map.copyOf(variants);
    }
}
