package com.pijava.ai.catalog;

import com.pijava.ai.model.PricingInfo;

/**
 * pi {@code AnthropicAllowedFallbackModelSchema}
 * （{@code compat-schema.ts:171-179}）：Anthropic 服务端拒绝回退的许可目标
 * ＋返回回退响应时做本地成本归属用的定价元数据。
 *
 * <p>请求里只发 {@code { "model": ... }}（{@code anthropic-messages.ts:1281-1284}）；
 * {@code provider}/{@code cost} 不出本机：provider 用于 {@code message_start} 里
 * responseModel 的成本归属（流内半随 B148），cost 是本地定价表。</p>
 *
 * @param provider 目标模型的 provider（成本归属匹配用）
 * @param model    目标模型 id（也是请求 {@code fallbacks[].model} 的上线值）
 * @param cost     本地成本表
 */
public record AllowedFallbackModel(String provider, String model, PricingInfo cost) {
}
