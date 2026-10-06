package com.pijava.agent.compaction;

import com.pijava.ai.Usage;

/**
 * 两段摘要调用的 usage 之和（B171，{@code docs/18}；pi {@code combineUsage}，
 * {@code usage-totals.ts:31-53}）：逐字段相加；{@code cacheWrite1h} 与
 * {@code reasoning} 仅当任一侧上报时才出现在结果中（缺席侧按 0），两侧皆无
 * ⇒ 结果保持 {@code null}（JSON 省略键）。
 */
final class UsageCombiner {

    private UsageCombiner() {}

    static Usage combine(Usage first, Usage second) {
        Double cacheWrite1h = first.cacheWrite1h() != null || second.cacheWrite1h() != null
            ? orZero(first.cacheWrite1h()) + orZero(second.cacheWrite1h())
            : null;
        Double reasoning = first.reasoning() != null || second.reasoning() != null
            ? orZero(first.reasoning()) + orZero(second.reasoning())
            : null;
        return new Usage(
            first.input() + second.input(),
            first.output() + second.output(),
            first.cacheRead() + second.cacheRead(),
            first.cacheWrite() + second.cacheWrite(),
            cacheWrite1h,
            reasoning,
            first.totalTokens() + second.totalTokens(),
            new Usage.Cost(
                first.cost().input() + second.cost().input(),
                first.cost().output() + second.cost().output(),
                first.cost().cacheRead() + second.cost().cacheRead(),
                first.cost().cacheWrite() + second.cost().cacheWrite(),
                first.cost().total() + second.cost().total()));
    }

    private static double orZero(Double value) {
        return value == null ? 0 : value;
    }
}
