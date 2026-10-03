package com.pijava.ai.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * pi.dev {@code ModelCost} wire 的费率子集（pi
 * {@code types.ts:941-946}）：每百万 token 单价；{@code tiers} 忽略。
 * 字段缺席时 primitive 默认 0。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RemoteCostWire(
    double input,
    double output,
    double cacheRead,
    double cacheWrite
) {
    /** 全零费率（cost 字段缺席时使用）。 */
    static final RemoteCostWire FREE = new RemoteCostWire(0, 0, 0, 0);
}
