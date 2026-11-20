package com.pijava.tui.component;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * L7 W1：{@code formatToolDuration} / {@code toolTimerText} 的逐字对位
 * （pi {@code core/tools/render-utils.ts:112-164}）。
 */
class ToolDurationsTest {

    @Test
    void belowAMinuteRendersOneDecimal() {
        assertThat(ToolDurations.format(0)).isEqualTo("0.0s");
        assertThat(ToolDurations.format(1_234)).isEqualTo("1.2s");
        assertThat(ToolDurations.format(42_499)).isEqualTo("42.5s");
    }

    /** pi 在 {@code seconds < 60} 分支不做取整，{@code toFixed(1)} 会进到 {@code 60.0s}。 */
    @Test
    void justUnderAMinuteStillTakesTheSecondsBranch() {
        assertThat(ToolDurations.format(59_999)).isEqualTo("60.0s");
    }

    @Test
    void minutesAndSecondsDropTheFraction() {
        assertThat(ToolDurations.format(60_000)).isEqualTo("1m 0s");
        assertThat(ToolDurations.format(90_500)).isEqualTo("1m 30s");
        assertThat(ToolDurations.format(3_599_000)).isEqualTo("59m 59s");
    }

    @Test
    void anHourAndBeyondRendersHoursMinutesSeconds() {
        assertThat(ToolDurations.format(3_600_000)).isEqualTo("1h 0m 0s");
        assertThat(ToolDurations.format(7_384_000)).isEqualTo("2h 3m 4s");
    }

    @Test
    void aPartialResultCountsUpFromTheStart() {
        assertThat(ToolDurations.timerText(true, null, 1_234)).isEqualTo("Elapsed 1.2s");
    }

    /** 流式中记录时长还没写回，始终用本渲染器的时钟。 */
    @Test
    void aPartialResultIgnoresAnAlreadyRecordedDuration() {
        assertThat(ToolDurations.timerText(true, 9_000L, 1_234)).isEqualTo("Elapsed 1.2s");
    }

    @Test
    void aFinalResultPrefersTheRecordedDuration() {
        assertThat(ToolDurations.timerText(false, 4_200L, 0L)).isEqualTo("Took 4.2s");
    }

    @Test
    void aFinalResultWithoutADurationFallsBackToTheClock() {
        assertThat(ToolDurations.timerText(false, null, 4_200)).isEqualTo("Took 4.2s");
    }
}
