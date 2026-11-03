package com.pijava.ai.protocol;

import java.util.Map;

import com.pijava.ai.Usage;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.model.CostCalculator;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.utils.OpenRouterBilling;

/**
 * Anthropic 车道的 per-stream usage 累加器 —— pi 把 {@code output.usage} 就地 mutate
 * 的语义（包 H1 步 3，{@code 原 docs/42 §2.1 P4–P7}）。
 *
 * <p>pi 在 {@code anthropic-messages.ts} 里两段写法<b>刻意不对称</b>，本类逐条照抄：</p>
 *
 * <ul>
 *   <li><b>{@code message_start}（{@code :615-625}）：五字段无条件 {@code || 0} 写入。</b>
 *       注释逐字：<i>"This ensures we have input token counts even if the stream is
 *       aborted early"</i>。{@code cacheWrite1h} 取自 {@code cache_creation.ephemeral_1h_input_tokens}；
 *       {@code reasoning} <b>不在这里读</b>（即便报文里有）。</li>
 *   <li><b>{@code message_delta}（{@code :849-889}）：五字段各自 {@code != null} 才覆盖。</b>
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

    /**
     * B148：服务端回退命中 {@code allowedFallbackModels} 条目后切换的定价表
     * （pi {@code usageModel = { …model, id: responseModel, cost: fallbackCost }}，
     * anthropic-messages.ts:674-677）；{@code null} ⇒ 沿用请求模型定价。
     */
    private PricingInfo pricingOverride;

    /**
     * docs/15（B267）：是否 OpenRouter 车道 —— 由 provider 预计算，
     * delta 的 {@code cost/is_byok/cost_details} 只在该车道应用。
     */
    private final boolean openRouterBilling;

    AnthropicUsageState(ModelInfo model) {
        this(model, false);
    }

    AnthropicUsageState(ModelInfo model, boolean openRouterBilling) {
        this.model = model;
        this.openRouterBilling = openRouterBilling;
    }

    /** B148：按 message_start 观测到的服务模型切换定价（null 还原请求模型表）。 */
    void usePricing(PricingInfo override) {
        this.pricingOverride = override;
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
     * pi {@code :849-889}（{@code message_delta}）：五字段各自 {@code != null} 才覆盖；
     * {@code cacheWrite1h} 在 delta 里<b>有就覆盖</b>（Vercel AI Gateway 形状，docs/15 B267）。
     *
     * @param input        {@code input_tokens}，{@code null} ⇒ 保留
     * @param output       {@code output_tokens}，{@code null} ⇒ 保留
     * @param cacheRead    {@code cache_read_input_tokens}，{@code null} ⇒ 保留
     * @param cacheWrite   {@code cache_creation_input_tokens}，{@code null} ⇒ 保留
     * @param cacheWrite1h {@code cache_creation.ephemeral_1h_input_tokens}，{@code null} ⇒ 保留
     * @param reasoning    {@code output_tokens_details.thinking_tokens}，{@code null} ⇒ 保留
     * @param reportedBilling provider 原始 usage 视图（含 OpenRouter 账单）；{@code null} ⇒
     *                        本事件无 usage，不尝试账单
     */
    void onMessageDelta(Long input, Long output, Long cacheRead, Long cacheWrite,
                        Long cacheWrite1h, Long reasoning, Map<String, Object> reportedBilling) {
        // ⚠️ 这里**刻意不用三元**：`Usage.reasoning()` 是 `Double`，只要另一支是原始 `double`
        // （哪怕写成 `reasoning.doubleValue()`），JLS 15.25 就会把整个条件表达式的类型提升成
        // `double` ⇒ 对被选中的那个 `Double` 拆箱 ⇒ null 时 NPE。两种写法**都**踩过。
        Double reasoningValue = usage.reasoning();
        if (reasoning != null) {
            reasoningValue = reasoning.doubleValue();
        }
        Double cacheWrite1hValue = usage.cacheWrite1h();
        if (cacheWrite1h != null) {
            cacheWrite1hValue = cacheWrite1h.doubleValue();
        }
        usage = new Usage(
            keep(input, usage.input()),
            keep(output, usage.output()),
            keep(cacheRead, usage.cacheRead()),
            keep(cacheWrite, usage.cacheWrite()),
            cacheWrite1hValue,
            reasoningValue,
            0, Usage.Cost.zero());
        reprice();
        // pi :878-888：目录分量与合计先成形，再让 provider 实报金额覆盖 total。
        if (openRouterBilling && reportedBilling != null) {
            usage = OpenRouterBilling.applyReportedCost(usage, reportedBilling);
        }
    }

    /** {@code totalTokens} 四分量和 ＋ 计价（pi 两处都在 {@code if (event.usage)} 块外无条件执行）。 */
    private void reprice() {
        double total = usage.input() + usage.output() + usage.cacheRead() + usage.cacheWrite();
        var withTotal = new Usage(usage.input(), usage.output(), usage.cacheRead(), usage.cacheWrite(),
            usage.cacheWrite1h(), usage.reasoning(), total, Usage.Cost.zero());
        // B148：命中 fallback 定价条目后用条目的 cost（pi usageModel 切换）。
        var pricing = pricingOverride != null ? pricingOverride
            : model == null ? null : model.pricing();
        usage = pricing == null
            ? withTotal
            : withTotal.withCost(CostCalculator.calculateCost(pricing, withTotal));
    }

    private static double orZero(Long value) {
        return value == null ? 0 : value;
    }

    private static double keep(Long value, double current) {
        return value == null ? current : value;
    }
}
