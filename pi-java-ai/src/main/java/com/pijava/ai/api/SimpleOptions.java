package com.pijava.ai.api;

import java.util.Map;
import java.util.Optional;

import com.pijava.ai.catalog.ModelInfo;
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
}
