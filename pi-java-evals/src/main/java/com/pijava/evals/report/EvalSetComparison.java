package com.pijava.evals.report;

import java.util.List;
import java.util.Objects;

/**
 * L6 W1（docs/26 §2）：一个 eval set 的配对汇总 —— pi {@code EvalSetComparison}
 * （{@code report.ts:43-56}）对位。
 *
 * <p>三处易错语义，逐条对位 pi：</p>
 * <ol>
 *   <li><b>headline 按 set 发布</b>：{@code publishHeadline = blockedPairs == 0
 *       && eligiblePairs > 0}（{@code report.ts:380}）。本 set 有 blocked 就只冻结
 *       本 set 的通过率与 lift，不牵连别的 set。</li>
 *   <li><b>lift 是通过率之差</b>（{@code report.ts:394-397}），不是逐对分数差均值。
 *       分数只取 0/1 时两者相同；有部分分时不同——pass rate 问「多少对从不及格变及格」。</li>
 *   <li><b>四组配对标量</b>（{@code report.ts:399-402}）只在 eligible 配对（两臂都
 *       {@code scored}）上统计，与 headline 是否发布无关。</li>
 * </ol>
 *
 * @param evalSet             eval set 名
 * @param totalPairs          计划内配对数
 * @param eligiblePairs       可统计配对数
 * @param blockedPairs        被阻断配对数（{@code totalPairs - eligiblePairs}）
 * @param controlPassRate     without_docs 通过率，headline 未发布时 {@code null}
 * @param treatmentPassRate   with_docs 通过率，headline 未发布时 {@code null}
 * @param lift                {@code treatmentPassRate - controlPassRate}，任一侧为 {@code null} 时 {@code null}
 * @param flags               旗标（语义同 {@code ComparisonReport.FLAG_*}）
 * @param totalTokens         token 配对标量
 * @param toolCalls           工具调用配对标量
 * @param totalMs             墙钟配对标量
 * @param estimatedCostUsd    成本配对标量
 */
public record EvalSetComparison(
    String evalSet,
    long totalPairs,
    long eligiblePairs,
    long blockedPairs,
    Double controlPassRate,
    Double treatmentPassRate,
    Double lift,
    List<String> flags,
    PairedMetricSummary totalTokens,
    PairedMetricSummary toolCalls,
    PairedMetricSummary totalMs,
    PairedMetricSummary estimatedCostUsd
) {

    /** Rejects nulls, normalizes flags, and checks pair arithmetic. */
    public EvalSetComparison {
        Objects.requireNonNull(evalSet, "evalSet");
        Objects.requireNonNull(flags, "flags");
        Objects.requireNonNull(totalTokens, "totalTokens");
        Objects.requireNonNull(toolCalls, "toolCalls");
        Objects.requireNonNull(totalMs, "totalMs");
        Objects.requireNonNull(estimatedCostUsd, "estimatedCostUsd");
        flags = List.copyOf(flags);
        if (totalPairs < 0 || eligiblePairs < 0 || blockedPairs < 0) {
            throw new IllegalArgumentException("pair counts must be >= 0");
        }
        if (eligiblePairs + blockedPairs != totalPairs) {
            throw new IllegalArgumentException(
                "eligiblePairs + blockedPairs must equal totalPairs");
        }
    }

    /**
     * {@code true} when the pass rate and lift may be published.
     *
     * @return whether this set has a headline
     */
    public boolean headlinePublished() {
        return blockedPairs == 0 && eligiblePairs > 0;
    }
}
