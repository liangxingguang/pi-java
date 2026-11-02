package com.pijava.ai.catalog;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * pi.dev {@code ModelCost} wire（pi {@code types.ts:941-946}）：每百万 token 单价
 * ＋ 可选的请求级档位。字段缺席时 primitive 默认 0。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RemoteCostWire(
    double input,
    double output,
    double cacheRead,
    double cacheWrite,
    List<Tier> tiers
) {

    /** 全零费率（cost 字段缺席时使用）。 */
    static final RemoteCostWire FREE = new RemoteCostWire(0, 0, 0, 0, List.of());

    public RemoteCostWire {
        tiers = tiers == null ? List.of() : List.copyOf(tiers);
    }

    /** 便捷构造：没有档位的旧形状。 */
    public RemoteCostWire(double input, double output, double cacheRead, double cacheWrite) {
        this(input, output, cacheRead, cacheWrite, List.of());
    }

    /** pi {@code ModelCostTier}（{@code types.ts:947-956}）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Tier(
        double inputTokensAbove,
        double input,
        double output,
        double cacheRead,
        double cacheWrite
    ) {}
}
