package com.pijava.ai.api;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import com.pijava.ai.catalog.ModelCompat;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.catalog.ModelThinkingLevels;
import com.pijava.ai.catalog.ThinkingTokenBudgetField;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.ai.thinking.ThinkingBudgets;
import com.pijava.ai.thinking.ThinkingLevel;
import com.pijava.ai.utils.Estimate;

/**
 * 请求侧选项的解析 —— pi {@code ai/src/api/simple-options.ts} 的可观察那半边（包 A-10）。
 *
 * <h2>为什么只有「半边」</h2>
 *
 * <p>pi 的 {@code buildBaseOptions(model, context, options?, apiKey?)} 返回一个 20 个键的
 * {@code StreamOptions}。java 里这一层<b>已经存在</b>：{@link StreamRequest} 就是那个载体
 * （它带 {@code maxTokens}/{@code temperature}/{@code reasoning}/{@code extra}），
 * 再发明一个对象只会多一处没有 pi 对应物的形状。故本类**不是** {@code buildBaseOptions}
 * 的形状移植，而是它那三处**可观察行为**的移植：</p>
 *
 * <table border="1">
 *   <caption>pi 的 {@code buildBaseOptions} 逐项对账</caption>
 *   <tr><th>pi</th><th>本类</th><th>说明</th></tr>
 *   <tr><td>{@code :34 maxTokens: clampMaxTokensToContext(model, context, options?.maxTokens ?? model.maxTokens)}</td>
 *       <td>{@link #resolveMaxTokens} ＝ {@link #maxTokensOrDefault} ＋ {@link #clampMaxTokensToContext}</td>
 *       <td>本包的核心</td></tr>
 *   <tr><td>{@code :27-30 samplingParams}（model ⨁ options 合并）</td>
 *       <td>{@link #samplingParamsOf}</td>
 *       <td>per-request 那一半在 java **没有生产者**（{@code StreamRequest.extra} 生产恒空）
 *           ⇒ 只落模型级，另一半登记（{@code docs/57 §6 R5}）</td></tr>
 *   <tr><td>{@code :32 temperature} 直通</td><td>不动</td>
 *       <td>{@code StreamRequest.temperature} 的读点两侧已一致</td></tr>
 *   <tr><td>{@code fetch}/{@code onPayload}/{@code transport}/{@code metadata}/{@code env} …</td>
 *       <td>不移植</td><td><b>子系统缺席</b>，不是偏差：java 没有可注入点（{@code docs/57 §3.2}）</td></tr>
 * </table>
 *
 * <h2>★ 落点：为什么解析发生在 {@code AbstractChatApi}</h2>
 *
 * <p>pi 把这一层放在**每条车道的 {@code streamSimple}** 里，而 java 的生产唯一入口
 * {@code AbstractChatApi.stream} 就对应 pi 的 {@code streamSimple}（驱动循环只调它，
 * {@code harness/runtime/drive/generation.ts:216}）。⇒ 在那里把 request 换成解析后的副本，
 * 8 条车道**零改签**地拿到解析值。</p>
 *
 * <p>⚠️ 两条车道**必须豁免**（{@code docs/57 §6 R2}）：{@code PiMessagesApi}
 * （pi 的 pi-messages 车道不过 {@code buildBaseOptions}，它的 envelope 里 {@code maxTokens}
 * 恒缺席）与 {@code FauxProvider}（测试替身）。不豁免前者会**凭空多发**一个字段。</p>
 */
public final class SimpleOptions {

    private SimpleOptions() {}

    /** pi {@code simple-options.ts:12} —— 夹取时的安全余量。 */
    public static final int CONTEXT_SAFETY_TOKENS = 4096;

    /** pi {@code simple-options.ts:13}。 */
    public static final int MIN_MAX_TOKENS = 1;

    /**
     * ★ 模型**没有声明**输出上限时的兜底（{@code docs/57 §6 R4}）。
     *
     * <p>⚠️ <b>这不是 pi 的行为，是一条刻意偏差。</b>pi 的 {@code Model.maxTokens} 必填无默认
     * （{@code types.ts:976}），它的数据面里不存在「0」这一格 ⇒ 没有可对齐的行为。
     * 而 java 这一格**今天就有生产路径**：{@code DefaultProviders:145} 对目录未命中的模型
     * 用 {@code ModelInfo.minimal}（{@code maxInputTokens == 0 && maxOutputTokens == 0}）。</p>
     *
     * <p>pi 的公式在那一格上会算出 {@code max_tokens: 1}（{@code contextWindow <= 0}
     * ⇒ {@code max(1, maxTokens)} 而 {@code maxTokens == 0}）——「一字一停」，
     * 而 {@code 0} 会被 Anthropic 直接 400。⇒ 取一个保守默认并如实登记它**不是** pi 的行为。
     * 数值沿用本包之前 {@code AnthropicRequestBuilder} 那个字面量，故目录未命中路径
     * **一个字节都不改**。</p>
     */
    public static final int NO_MODEL_CAP_FALLBACK = 4096;

    /**
     * pi {@code simple-options.ts:15-19} {@code clampMaxTokensToContext}。
     *
     * <pre>{@code
     * if (model.contextWindow <= 0) return Math.max(1, maxTokens);
     * const available = model.contextWindow - estimateContextTokens(context).tokens - 4096;
     * return Math.min(maxTokens, Math.max(1, available));
     * }</pre>
     *
     * <p>java 的 {@code ModelInfo.maxInputTokens} <b>就是</b> pi 的 {@code contextWindow}
     * （单位 token，javadoc 明写），{@code <= 0} 同样是「未知」哨兵（目录未命中）。</p>
     *
     * <p>⚠️ 一处不可避免的取整：pi 的 {@code available} 是 JS 的 number，可以带小数
     * （{@code estimateContextTokens} 的结果就是小数），于是 pi 可能发出
     * {@code max_tokens: 12000.5}；java 的 SDK 只收整数 ⇒ 这里取 {@code floor}
     * <b>保守</b>方向。只在「可用余量非整数」时差至多 1。</p>
     */
    public static int clampMaxTokensToContext(ModelInfo model, TranscriptContext transcript, int maxTokens) {
        var contextWindow = model == null ? 0 : model.maxInputTokens();
        if (contextWindow <= 0) {
            return Math.max(MIN_MAX_TOKENS, maxTokens);
        }
        var available = contextWindow - Estimate.estimateContextTokens(transcript).tokens() - CONTEXT_SAFETY_TOKENS;
        return (int) Math.floor(Math.min(maxTokens, Math.max(MIN_MAX_TOKENS, available)));
    }

    /**
     * 请求没给上限时回落到模型自己的输出上限 —— pi 的 {@code options?.maxTokens ?? model.maxTokens}
     * （{@code simple-options.ts:34} 的合取项，也是 {@code anthropic-messages.ts:1072}
     * 在**低层** {@code stream} 里的那份回落）。
     *
     * <p>★ 这一行是本包最重的修复：修复前 {@code maxTokens} 在生产上没有生产者
     * （三个 {@code StreamOptions} 构造点全部传 {@code OptionalInt.empty()}），
     * Anthropic 车道因此发一个自家发明的 {@code 4096}，而其余五条车道干脆一个上限都不发。</p>
     */
    public static int maxTokensOrDefault(ModelInfo model, int requested) {
        if (requested > 0) {
            return requested;
        }
        var modelCap = model == null ? 0 : model.maxOutputTokens();
        return modelCap > 0 ? modelCap : NO_MODEL_CAP_FALLBACK;
    }

    /** 回落 ＋ 夹取（pi {@code buildBaseOptions:34} 的整个 {@code maxTokens} 表达式）。 */
    public static int resolveMaxTokens(ModelInfo model, TranscriptContext transcript, int requested) {
        return clampMaxTokensToContext(model, transcript, maxTokensOrDefault(model, requested));
    }

    /**
     * 把请求换成一个 {@code maxTokens} 已解析的副本（幂等）。
     *
     * <p>值没变时返回原对象 —— 让「解析」这层在多数请求上不产生额外分配。</p>
     */
    public static StreamRequest resolveRequest(StreamRequest request) {
        var resolved = resolveMaxTokens(request.model(), request.transcript(), request.maxTokens());
        return resolved == request.maxTokens() ? request : request.withMaxTokens(resolved);
    }

    /**
     * 模型级采样参数的直通（pi {@code buildBaseOptions:27-30} 的合并结果）。
     *
     * <p>pi 的合并是 {@code model.samplingParams || options?.samplingParams ? {...model, ...options} : undefined}。
     * java 的 per-request 那一半**没有生产者**（{@code StreamRequest.extra} 在生产恒为
     * {@code Map.of()}，{@link StreamRequest} 也没有第二个采样参数通道）⇒ 结果就是模型级那一份。
     * 「per-request 压过模型级」这一支登记为 B125，本包不造通道。</p>
     *
     * <p>只在**非空**时返回 —— 与 pi 的落点等价：车道那边的写法是
     * {@code if (options?.samplingParams) Object.assign(params, ...)}，
     * 空对象合并进去是恒等操作。</p>
     */
    public static Optional<Map<String, Object>> samplingParamsOf(ModelInfo model) {
        var params = model == null ? null : model.samplingParams();
        return params == null || params.isEmpty() ? Optional.empty() : Optional.of(params);
    }

    // ── 顶层思考预算字段（pi openai-completions.ts:1004-1024，包 A-10）─────

    /**
     * pi {@code openai-completions.ts:1004-1010} {@code resolveThinkingTokenBudgetField}：
     * 显式字段名优先，否则布尔别名 ⇒ {@code thinking_token_budget}（vLLM 拼写），否则无。
     *
     * <p>⚠️ 本字段**与 {@code thinkingFormat} 无关**（pi {@code :972-975} 的注释：同一台
     * 服务器可能同时服务 zai／qwen／chat-template 形态的模型）⇒ 它在形态链条**之外**，
     * 包 A-09 不重复落它。</p>
     */
    public static Optional<ThinkingTokenBudgetField> thinkingTokenBudgetField(ModelCompat compat) {
        if (compat == null) {
            return Optional.empty();
        }
        if (compat.thinkingTokenBudgetField() != null) {
            return Optional.of(compat.thinkingTokenBudgetField());
        }
        return Boolean.TRUE.equals(compat.supportsThinkingTokenBudget())
            ? Optional.of(ThinkingTokenBudgetField.THINKING_TOKEN_BUDGET)
            : Optional.empty();
    }

    /**
     * pi {@code openai-completions.ts:741-742} —— 车道的 {@code reasoningEffort}
     * 是**夹取过**的级别，且 {@code "off"} 要变成 {@code undefined}。
     *
     * <pre>{@code
     * const clampedReasoning = options?.reasoning ? clampThinkingLevel(model, options.reasoning) : undefined;
     * const reasoningEffort = clampedReasoning === "off" ? undefined : clampedReasoning;
     * }</pre>
     *
     * <p>⚠️ <b>接线这一处原本记在 A-09 名下</b>（{@code docs/57 §1.2}），本包提前落地：
     * 顶层预算字段的**取值**要以夹取后的级别为准（{@code budgetForLevel} 只认模型支持的
     * 级别），不夹就会在这个字段上算出与 pi 不同的预算。A-09 届时直接复用本方法，
     * 不要再接一次（{@code docs/57 §12} 记为「归属前移」）。</p>
     *
     * <p>返回空 ≙ pi 的 {@code "off"} 或 {@code undefined}（java 的 {@link ThinkingLevel}
     * 没有 {@code off} 这一档，它是{@link com.pijava.ai.thinking.ModelThinkingLevel} 的成员）。</p>
     */
    public static Optional<ThinkingLevel> clampedReasoningEffort(ModelInfo model,
                                                                Optional<ThinkingLevel> reasoning) {
        if (model == null || reasoning.isEmpty()) {
            return Optional.empty();
        }
        var clamped = ModelThinkingLevels.clamp(model, ModelThinkingLevel.of(reasoning.get()));
        return clamped instanceof ModelThinkingLevel.Enabled enabled
            ? Optional.of(enabled.level()) : Optional.empty();
    }

    /**
     * pi {@code openai-completions.ts:1012-1024} {@code resolveClampedThinkingBudget}：
     * 预算按级别算出来，再夹到「天花板 − {@code MIN_ANSWER_TOKENS}」，非正 ⇒ 不发。
     *
     * <pre>{@code
     * if (!options?.reasoningEffort || !model.reasoning) return undefined;
     * const ceiling = params.max_tokens ?? params.max_completion_tokens ?? model.maxTokens;
     * const budget = clampThinkingBudgetToAnswerRoom(
     *     thinkingBudgetForLevel(options.reasoningEffort, options.thinkingBudgets), ceiling);
     * return budget > 0 ? budget : undefined;
     * }</pre>
     *
     * @param ceiling 线格上的输出上限。调用方给 {@link #maxTokensOrDefault} 的结果 ——
     *                它与 pi 的 {@code params.max_tokens ?? params.max_completion_tokens
     *                ?? model.maxTokens} 同值（生产路径上漏斗已把请求上限解析成夹取值，
     *                故两侧的天花板同为**夹取后**的上限）。
     * @param budgets 自定义预算表；java 今天没有选项通道（{@code docs/57 §6 R7} 登记）
     *                ⇒ 调用方传 {@link ThinkingBudgets#DEFAULT}，与 pi 的
     *                {@code options.thinkingBudgets === undefined} 等价。
     */
    public static OptionalInt clampedThinkingBudget(ModelInfo model, ModelCompat compat,
                                                    Optional<ThinkingLevel> reasoning,
                                                    ThinkingBudgets budgets, int ceiling) {
        if (model == null || compat == null
            || !model.capabilities().contains(ModelCapability.THINKING)) {
            return OptionalInt.empty();
        }
        var level = clampedReasoningEffort(model, reasoning);
        if (level.isEmpty()) {
            return OptionalInt.empty();
        }
        var budget = ThinkingBudgets.clampThinkingBudgetToAnswerRoom(
            budgets.budgetFor(level.get()), ceiling);
        return budget > 0 ? OptionalInt.of(budget) : OptionalInt.empty();
    }
}
