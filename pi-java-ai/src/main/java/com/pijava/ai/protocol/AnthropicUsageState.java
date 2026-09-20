package com.pijava.ai.protocol;

import com.pijava.ai.Usage;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.model.CostCalculator;

/**
 * Anthropic 车道的 per-stream usage 累加器 —— pi 把 {@code output.usage} 就地 mutate
 * 的语义（包 H1 步 3，{@code docs/42 §2.1 P4–P7}）。
 *
 * <p>pi 在 {@code anthropic-messages.ts} 里两段写法<b>刻意不对称</b>，本类逐条照抄：</p>
 *
 * <ul>
 *   <li><b>{@code message_start}（{@code :615-625}）：五字段无条件 {@code || 0} 写入。</b>
 *       注释逐字：<i>"This ensures we have input token counts even if the stream is
 *       aborted early"</i>。{@code cacheWrite1h} 取自 {@code cache_creation.ephemeral_1h_input_tokens}，
 *       <b>只在这里设</b>；{@code reasoning} <b>不在这里读</b>（即便报文里有）。</li>
 *   <li><b>{@code message_delta}（{@code :763-787}）：四字段各自 {@code != null} 才覆盖。</b>
 *       注释逐字：<i>"Only update usage fields if present (not null). Preserves
 *       input_tokens from message_start when proxies omit it in message_delta."</i>
 *       ⇒ <b>{@code 0} 是合法值、必须覆盖</b>（不是 JS 真值判断）。{@code reasoning} 走
 *       {@code output_tokens_details.thinking_tokens}（<b>与四字段不同层</b>）。</li>
 *   <li><b>{@code totalTokens} 重算与计价在 {@code if (event.usage)} 块外</b>
 *       （{@code :784-787}）⇒ 无论本次事件带不带 usage 都执行。</li>
 * </ul>
 *
 * <p>Anthropic 不提供 {@code total_tokens} ⇒ {@code totalTokens} 恒为四分量和
 * （{@code :622-624}）。</p>
 */
final class AnthropicUsageState {

    /** pi 的流起点初值（{@code :529-536}）：四分量 + totalTokens 全 0、cost 全 0；**不含** cacheWrite1h/reasoning 键。 */
    private Usage usage = new Usage(0, 0, 0, 0, null, null, 0, Usage.Cost.zero());

    /** 计价用的模型；{@code null} ⇒ 不计价（cost 保持零）。 */
    private final ModelInfo model;

    AnthropicUsageState(ModelInfo model) {
        this.model = model;
    }

    /** 当前累计的 usage（pi 的 {@code output.usage}）。 */
    Usage usage() {
        return usage;
    }

    /**
     * pi {@code :615-625}（{@code message_start}）：五字段无条件写入，缺失落 0。
     *
     * @param input        {@code input_tokens}
     * @param output       {@code output_tokens}
     * @param cacheRead    {@code cache_read_input_tokens}
     * @param cacheWrite   {@code cache_creation_input_tokens}
     * @param cacheWrite1h {@code cache_creation.ephemeral_1h_input_tokens}
     */
    void onMessageStart(Long input, Long output, Long cacheRead, Long cacheWrite, Long cacheWrite1h) {
        usage = new Usage(orZero(input), orZero(output), orZero(cacheRead), orZero(cacheWrite),
            orZero(cacheWrite1h), usage.reasoning(), 0, Usage.Cost.zero());
        reprice();
    }

    /**
     * pi {@code :763-787}（{@code message_delta}）：四字段各自 {@code != null} 才覆盖；
     * {@code cacheWrite1h} 不在此列。
     *
     * @param input     {@code input_tokens}，{@code null} ⇒ 保留
     * @param output    {@code output_tokens}，{@code null} ⇒ 保留
     * @param cacheRead {@code cache_read_input_tokens}，{@code null} ⇒ 保留
     * @param cacheWrite {@code cache_creation_input_tokens}，{@code null} ⇒ 保留
     * @param reasoning {@code output_tokens_details.thinking_tokens}，{@code null} ⇒ 保留
     */
    void onMessageDelta(Long input, Long output, Long cacheRead, Long cacheWrite, Long reasoning) {
        // ⚠️ 这里**刻意不用三元**：`Usage.reasoning()` 是 `Double`，只要另一支是原始 `double`
        // （哪怕写成 `reasoning.doubleValue()`），JLS 15.25 就会把整个条件表达式的类型提升成
        // `double` ⇒ 对被选中的那个 `Double` 拆箱 ⇒ null 时 NPE。两种写法**都**踩过。
        Double reasoningValue = usage.reasoning();
        if (reasoning != null) {
            reasoningValue = reasoning.doubleValue();
        }
        usage = new Usage(
            keep(input, usage.input()),
            keep(output, usage.output()),
            keep(cacheRead, usage.cacheRead()),
            keep(cacheWrite, usage.cacheWrite()),
            usage.cacheWrite1h(),
            reasoningValue,
            0, Usage.Cost.zero());
        reprice();
    }

    /** {@code totalTokens} 四分量和 ＋ 计价（pi 两处都在 {@code if (event.usage)} 块外无条件执行）。 */
    private void reprice() {
        double total = usage.input() + usage.output() + usage.cacheRead() + usage.cacheWrite();
        var withTotal = new Usage(usage.input(), usage.output(), usage.cacheRead(), usage.cacheWrite(),
            usage.cacheWrite1h(), usage.reasoning(), total, Usage.Cost.zero());
        usage = model == null
            ? withTotal
            : withTotal.withCost(CostCalculator.calculateCost(model.pricing(), withTotal));
    }

    private static double orZero(Long value) {
        return value == null ? 0 : value;
    }

    private static double keep(Long value, double current) {
        return value == null ? current : value;
    }
}
