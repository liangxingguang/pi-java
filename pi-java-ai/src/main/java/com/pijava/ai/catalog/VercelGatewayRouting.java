package com.pijava.ai.catalog;

import java.util.List;

/**
 * pi {@code OpenAICompletionsCompat.vercelGatewayRouting}
 * （{@code compat-schema.ts:151-169} 的 {@code VercelGatewayRoutingSchema}）：
 * Vercel AI Gateway 的上游路由偏好，落线成
 * {@code providerOptions: { gateway: { only?, order? } }}
 * （{@code openai-completions.ts:993-1001}）。
 *
 * <p>显式-only 字段：探测面无默认、生成目录零产出，只来自 models.json；
 * 两个列表都缺席/空时消费点不发 {@code providerOptions}（≙ pi 的
 * {@code if (routing.only || routing.order)}）。</p>
 *
 * @param only  provider slug 白名单（只用这些），{@code null} ≙ pi 的 {@code undefined}
 * @param order provider slug 优先序（按序尝试），{@code null} ≙ 未写
 */
public record VercelGatewayRouting(List<String> only, List<String> order) {

    public VercelGatewayRouting {
        only = only == null ? null : List.copyOf(only);
        order = order == null ? null : List.copyOf(order);
    }

    /** pi {@code if (routing.only || routing.order)}：两边都空 ⇒ 不发键。 */
    public boolean hasRouting() {
        return (only != null && !only.isEmpty()) || (order != null && !order.isEmpty());
    }
}
