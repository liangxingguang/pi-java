package com.pijava.telemetry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * pi {@code memory.ts:96-122} {@code telemetryNow()} 的行为钉床：墙钟对齐、
 * 微秒取整、同进程单调。
 */
class TelemetryClockTest {

    @Test
    void returnsWallClockRoundedToMicroseconds() {
        double now = TelemetryClock.now();

        assertThat(now).isCloseTo(System.currentTimeMillis(), within(100.0));
        // 微秒取整：×1000 后应为整数（memory.ts:120）。
        assertThat(Math.rint(now * 1000.0)).isEqualTo(now * 1000.0);
    }

    @Test
    void neverGoesBackwardsAcrossCalls() {
        double previous = TelemetryClock.now();
        for (int i = 0; i < 1000; i++) {
            double current = TelemetryClock.now();
            assertThat(current).isGreaterThanOrEqualTo(previous);
            previous = current;
        }
    }
}
