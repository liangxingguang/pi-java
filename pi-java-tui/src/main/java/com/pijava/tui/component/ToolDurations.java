package com.pijava.tui.component;

import java.util.Locale;

/**
 * L7 W1（pi {@code core/tools/render-utils.ts:112-164}，docs/59 §3）——工具行的耗时文案。
 *
 * <p>pi 的 {@code formatToolDuration} 三档逐字：{@code <60s ⇒ "X.Ys"}、
 * {@code <60m ⇒ "Nm Rs"}、否则 {@code "Hh Mm Rs"}。行内计时文案
 * {@code toolTimerText} 两臂：流中（{@code isPartial}）为 {@code "Elapsed X.Ys"} 且按秒重绘；
 * 终局优先用结果上记录的 {@code durationMs}（重载后仍准），否则用自开始时刻的差值。</p>
 *
 * <p><b>Java 接缝</b>：计时来源是持久化 {@code Message.ToolResultMessage.durationMs}
 * （B234，pi {@code types.ts:635}；只围 {@code execute()} 本体），经
 * {@code ChatMessage.ToolResult} 传到结果行的 {@link #timerText} 终局优先臂。
 * 「Elapsed」流中臂由实时工具卡（{@link ChatMessage.ToolRunning}）在每次 update 时
 * 现读实测耗时产出一行；Java 的逻辑行模型不做 pi 那种逐行 1 秒 {@code invalidate}
 * 重绘，只在事件到达时刷新。</p>
 */
public final class ToolDurations {

    private ToolDurations() {}

    /** pi {@code formatToolDuration}：毫秒 ⇒ 人类可读时长。 */
    public static String format(long millis) {
        var seconds = millis / 1000.0d;
        if (seconds < 60) {
            return String.format(Locale.ROOT, "%.1fs", seconds);
        }
        var totalSeconds = (long) Math.floor(seconds);
        var minutes = totalSeconds / 60;
        var remainder = totalSeconds % 60;
        if (minutes < 60) {
            return minutes + "m " + remainder + "s";
        }
        return (minutes / 60) + "h " + (minutes % 60) + "m " + remainder + "s";
    }

    /**
     * pi {@code toolTimerText}：行内计时文案。
     *
     * @param partial       结果是否仍在流式中（{@code Elapsed} 臂）
     * @param durationMillis 结果上记录的耗时（终局优先，{@code null} ≙ 键省略）
     * @param elapsedMillis  自开始时刻的实测耗时（{@code durationMillis} 缺席时使用）
     * @return {@code "Elapsed 1.2s"} 或 {@code "Took 4.2s"}
     */
    public static String timerText(boolean partial, Long durationMillis, long elapsedMillis) {
        if (!partial && durationMillis != null) {
            return "Took " + format(durationMillis);
        }
        return (partial ? "Elapsed " : "Took ") + format(elapsedMillis);
    }
}
