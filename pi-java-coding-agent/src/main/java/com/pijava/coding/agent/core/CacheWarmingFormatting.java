package com.pijava.coding.agent.core;

import java.util.Locale;

import com.pijava.agent.entry.Entry;

/**
 * 批 13（docs/24 T1）：pi {@code cache-warmer.ts:403-453} 的格式化函数族
 * （{@code /session} 状态行与转录用量行）＋ {@code components/footer.ts:25-31} 的
 * {@code formatTokens}（miss 通知与 {@code /session} 共用）。逐字移植。
 */
public final class CacheWarmingFormatting {

    private CacheWarmingFormatting() {}

    /** pi :403-405。 */
    private static String formatDollars(double value) {
        return value < 0
            ? "-$" + String.format(Locale.ROOT, "%.3f", Math.abs(value))
            : "$" + String.format(Locale.ROOT, "%.3f", value);
    }

    /** pi :407-416。 */
    private static String formatEconomics(CacheWarming.Decision decision) {
        if (!decision.economicsAvailable()) {
            return "cache economics unavailable";
        }
        var probability = Math.round(decision.continuationProbability() * 100);
        var probabilityText = "streaming".equals(decision.phase())
            ? probability + "% continuation probability while agent is running"
            : probability + "% continuation probability";
        var comparison = decision.action() == CacheWarming.Action.WARM ? ">=" : "<";
        return probabilityText + ", expected savings " + formatDollars(decision.expectedSavings())
            + " " + comparison + " $"
            + String.format(Locale.ROOT, "%.3f", CacheWarmer.MINIMUM_EXPECTED_SAVINGS);
    }

    /** pi :418-430。 */
    private static String formatDecisionTime(Long nextWarmAt, long now) {
        if (nextWarmAt == null || nextWarmAt <= now) {
            return "Decision now";
        }
        var remainingSeconds = (long) Math.ceil((nextWarmAt - now) / 1000.0);
        var hours = remainingSeconds / 3600;
        remainingSeconds %= 3600;
        var minutes = remainingSeconds / 60;
        var seconds = remainingSeconds % 60;
        var parts = new StringBuilder();
        if (hours > 0) {
            parts.append(hours).append('h');
        }
        if (minutes > 0) {
            if (parts.length() > 0) {
                parts.append(' ');
            }
            parts.append(minutes).append('m');
        }
        if (seconds > 0 || parts.length() == 0) {
            if (parts.length() > 0) {
                parts.append(' ');
            }
            parts.append(seconds).append('s');
        }
        return "Decision in " + parts;
    }

    /**
     * pi :433-446 的 {@code formatCacheWarmingStatus}——{@code /session} 单行状态。
     * {@code now} 默认 {@code System.currentTimeMillis()}（pi 的 {@code Date.now()}）。
     */
    public static String formatStatus(CacheWarming.Status status) {
        return formatStatus(status, System.currentTimeMillis());
    }

    /** 带时钟形（测试与 {@code /session} 渲染同点现读）。 */
    public static String formatStatus(CacheWarming.Status status, long now) {
        var decision = status.decision();
        // 决策在 pi（或扩展）行动之后才挂上；"inactive" 且无决策 ⇒ 从未走到那步。
        if (decision == null || ("inactive".equals(status.state())
                && !decision.economicsAvailable()
                && !Boolean.TRUE.equals(status.extensionOverride()))) {
            return "Inactive (" + (status.reason() != null ? status.reason() : "unknown reason")
                + ")";
        }
        var details = Boolean.TRUE.equals(status.extensionOverride())
            ? "extension override, " + formatEconomics(decision)
            : formatEconomics(decision) + " -> " + decision.action().wireName();
        if ("inactive".equals(status.state())) {
            return "Stopped (" + details + ")";
        }
        if ("refreshing".equals(status.state())) {
            return "Warming cache (" + details + ")";
        }
        return formatDecisionTime(status.nextWarmAt(), now) + " (" + details + ")";
    }

    /**
     * pi :449-453 的 {@code formatCacheWarmingUsage}——落盘暖用量的转录单行。
     * 金额＝{@code toFixed(6)} 去尾零（保留 ≥3 位小数，逐字正则
     * {@code (\.\d{3}\d*?)0+$ ⇒ $1}）。
     */
    public static String formatUsage(Entry.Usage entry) {
        var note = entry.note() != null ? " (" + entry.note() + ")" : "";
        var cost = String.format(Locale.ROOT, "%.6f", entry.usage().cost().total())
            .replaceAll("(\\.\\d{3}\\d*?)0+$", "$1");
        return "Cache warmed" + note + ": $" + cost;
    }

    /**
     * pi {@code components/footer.ts:25-31} 的 {@code formatTokens} 逐字——
     * 紧凑 token 计数（miss 通知与 {@code /session} 共用）。
     */
    public static String formatTokens(long count) {
        if (count < 1_000) {
            return Long.toString(count);
        }
        if (count < 10_000) {
            return String.format(Locale.ROOT, "%.1fk", count / 1000.0);
        }
        if (count < 1_000_000) {
            return Math.round(count / 1000.0) + "k";
        }
        if (count < 10_000_000) {
            return String.format(Locale.ROOT, "%.1fM", count / 1_000_000.0);
        }
        return Math.round(count / 1_000_000.0) + "M";
    }
}
