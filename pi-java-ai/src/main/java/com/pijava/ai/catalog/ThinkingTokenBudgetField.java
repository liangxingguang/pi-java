package com.pijava.ai.catalog;

import java.util.Locale;

/**
 * 顶层请求字段名：用来给推理阶段单独设一个 token 上限（pi
 * {@code OpenAICompletionsCompat.thinkingTokenBudgetField}，{@code types.ts:97-98}）。
 *
 * <p>pi 的值是字符串联合
 * {@code "thinking_token_budget" | "thinking_budget" | "thinking_budget_tokens"}
 * （分别是 vLLM／Qwen-DashScope-SGLang／llama.cpp 的拼写）；本仓按 {@code CLAUDE.md}
 * 的约定落成枚举（三个取值的纯常量闭集），{@link #wireName()} 给出线格字面量
 * —— 与 {@link MaxTokensField} 同一手法，且三个名字的小写**正好**就是线格字面量。</p>
 *
 * <p>为什么需要它（pi {@code types.ts:718-724} 的注释）：这些端点上**推理与答案共用
 * {@code max_tokens}** ⇒ 不设预算时，推理重的回合可以把整个响应吃光、一个答案与工具调用
 * 都不留。</p>
 *
 * <p>⚠️ <b>它与 {@code thinkingFormat} 无关</b>（pi {@code openai-completions.ts:972-975}
 * 的注释）：同一台服务器可能同时服务 zai／qwen／chat-template 三种形态的模型 ⇒
 * 本字段的落点在形态链条**之外**，「openai」形态也有（包 A-10 落，A-09 不动它）。</p>
 *
 * @see com.pijava.ai.catalog.ModelCompat#thinkingTokenBudgetField()
 */
public enum ThinkingTokenBudgetField {
    /** pi {@code "thinking_token_budget"} —— vLLM；也是布尔别名 {@code supportsThinkingTokenBudget} 的取值。 */
    THINKING_TOKEN_BUDGET,

    /** pi {@code "thinking_budget"} —— Qwen／DashScope／SGLang。 */
    THINKING_BUDGET,

    /** pi {@code "thinking_budget_tokens"} —— llama.cpp。 */
    THINKING_BUDGET_TOKENS;

    /** 线格字面量（pi 的那个字符串）。 */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
