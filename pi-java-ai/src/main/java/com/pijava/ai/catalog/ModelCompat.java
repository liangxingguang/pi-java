package com.pijava.ai.catalog;

/**
 * Per-model provider compatibility flags (pi {@code Model.compat}, {@code types.ts:713-714}).
 *
 * <p>Only flags pi-java actually **consults** are carried here — three so far. pi's own interface
 * has eight ({@code forceAdaptiveThinking}, {@code supportsStrictTools}, {@code deferredToolsMode},
 * …); each gets added when its consumer is ported, never speculatively
 * (docs/31 §8.34.4 决策 2). The field is a typed record rather than a {@code Map} for the same
 * reason: pi's compat is a per-provider typed interface, and a map would push type errors to the
 * read site.</p>
 *
 * <p>⚠️ <b>三个标志的「缺席」语义各不相同</b> —— 这是本记录最容易读错的地方，逐个写清：</p>
 *
 * <table border="1">
 *   <caption>absent-case semantics</caption>
 *   <tr><th>标志</th><th>类型</th><th>缺席 ≙</th><th>为什么</th></tr>
 *   <tr><td>{@code allowEmptySignature}</td><td>{@code boolean}</td><td>{@code false}</td>
 *       <td>pi {@code ?? false}（{@code anthropic-messages.ts:193}）⇒ 二态</td></tr>
 *   <tr><td>{@code requiresReasoningContentOnAssistantMessages}</td><td>{@link Boolean}</td>
 *       <td><b>探测</b>（provider/baseUrl）</td>
 *       <td>pi 的探测结果**依赖模型**（{@code isDeepSeek}，{@code detectCompat:1644}）⇒ 三态</td></tr>
 *   <tr><td>{@code supportsFinishReason}</td><td>{@code boolean}</td><td>{@code true}</td>
 *       <td>pi 的探测结果是**常量 `true`**（{@code detectCompat:1638}，无任何分支）⇒
 *           {@code explicit ?? true} 塌缩成二态、且方向与第一个标志**相反**</td></tr>
 * </table>
 *
 * @param allowEmptySignature pi {@code compat.allowEmptySignature}. When {@code true}, a thinking
 *        block that has **no** signature is replayed as a {@code thinking} block carrying
 *        {@code signature:""} instead of being downgraded to plain text — some
 *        Anthropic-compatible providers emit and accept empty signatures. Absent ≡ {@code false}
 *        (pi normalizes with {@code ?? false}, {@code anthropic-messages.ts:193}), so this is a
 *        **two-state** flag, not tri-state: {@code undefined} and {@code false} are
 *        indistinguishable in behavior (docs/31 §8.34.4 决策 3).
 * @param requiresReasoningContentOnAssistantMessages pi
 *        {@code compat.requiresReasoningContentOnAssistantMessages}
 *        ({@code openai-completions.ts:1356-1362}): when set, every assistant history message
 *        that carries no reasoning is sent with an **empty** {@code reasoning_content}, because
 *        DeepSeek-style relays reject the turn (400) without it.
 *
 *        <p>⚠️ Unlike {@code allowEmptySignature}, the absent case is **not** "false": pi
 *        resolves it against a detection ({@code detectCompat:1643} — provider {@code deepseek}
 *        or a baseUrl containing {@code deepseek.com}), so this component is a **three-state**
 *        {@link Boolean}: {@code null} = detect, {@code true}/{@code false} = explicit user
 *        override from {@code models.json} ({@code getCompat:1691-1700} does
 *        {@code explicit ?? detected}). Reading it as a plain boolean would silently disable
 *        the whole deepseek path.</p>
 * @param supportsFinishReason pi {@code compat.supportsFinishReason}
 *        ({@code openai-completions.ts:685-692}, openai-completions lane only): when {@code true},
 *        a stream that ends **without** any {@code finish_reason} is an error
 *        ({@code "Stream ended without finish_reason"}) rather than a silent success; when
 *        {@code false}, the lane falls back to {@code toolCall ? "toolUse" : "stop"}. Absent ≡
 *        {@code true} — the opposite default from {@code allowEmptySignature}.
 *
 *        <p>⚠️ **为什么它能塌缩成普通 `boolean`，而上面那个组件必须三态**：`explicit ?? detected`
 *        要保三态的前提是 **detected 随模型变**（上一个组件的 detected 是 {@code isDeepSeek}）。
 *        本标志的 detected 是 `detectCompat:1638` 里的字面量 `true` —— 函数体内**没有任何分支**
 *        碰过它 ⇒ `explicit ?? true` 里 `undefined` 与 `true` 行为完全相同，没有第三种状态可保。
 *        于是「缺席 ≙ {@code true}」被归一在 {@link com.pijava.ai.provider.ModelsJsonConfig} 的
 *        {@code compatOf} 里，读侧永远拿到一个非空的布尔。
 *        反过来，正因为默认是 `true`，**唯一**能放宽严格检查的途径就是用户显式写 {@code false}。</p>
 */
public record ModelCompat(boolean allowEmptySignature,
                          Boolean requiresReasoningContentOnAssistantMessages,
                          boolean supportsFinishReason) {

    /**
     * The default for models that declare nothing.
     *
     * <p>⚠️ 注意第三位是 {@code true}：{@code NONE} 的意思是「用户没写任何 compat」，
     * 不是「所有标志都关」——{@code supportsFinishReason} 的探测默认值就是开。
     * 写成 {@code false} 会让**每一条**没写 compat 的 models.json 模型静默退回容忍版。</p>
     */
    public static final ModelCompat NONE = new ModelCompat(false, null, true);

    /** Flags with {@code allowEmptySignature} set, the rest left to detection. */
    public static ModelCompat of(boolean allowEmptySignature) {
        return new ModelCompat(allowEmptySignature, null, true);
    }
}
