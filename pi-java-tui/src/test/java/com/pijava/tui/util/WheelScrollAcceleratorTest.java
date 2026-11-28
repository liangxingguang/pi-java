package com.pijava.tui.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

/** Port of pi {@code packages/tui/test/wheel-scroll.test.ts} (K19). */
class WheelScrollAcceleratorTest {

    private static int[] scroll(WheelScrollAccelerator accelerator, int direction, long... times) {
        return Arrays.stream(times).mapToInt(time -> accelerator.next(direction, time)).toArray();
    }

    @Test
    void usesFixedLineCountsRegardlessOfTiming() {
        var accelerator = WheelScrollAccelerator.fixed(3);
        assertThat(scroll(accelerator, 1, 0, 10, 20, 1000)).containsExactly(3, 3, 3, 3);
        accelerator.setLines(1);
        assertThat(accelerator.next(1, 2000)).isEqualTo(1);
    }

    @Test
    void keepsOneLinePerEventWhenTerminalAlreadyAccelerates() {
        var accelerator = WheelScrollAccelerator.auto(false);
        assertThat(scroll(accelerator, 1, 0, 10, 20, 30)).containsExactly(1, 1, 1, 1);
    }

    @Test
    void scalesAutoModeWithWheelVelocity() {
        var accelerator = WheelScrollAccelerator.auto(true);
        assertThat(scroll(accelerator, 1, 0, 150, 300, 450)).containsExactly(1, 1, 1, 1);
        assertThat(scroll(accelerator, 1, 1000, 1050, 1100, 1150)).containsExactly(1, 2, 2, 2);
        assertThat(scroll(accelerator, 1, 2000, 2020, 2040, 2060)).containsExactly(1, 5, 5, 5);
        assertThat(scroll(accelerator, 1, 3000, 3010, 3020, 3030)).containsExactly(1, 6, 6, 6);
    }

    @Test
    void doesNotAccelerateBurstsForSingleNotch() {
        var accelerator = WheelScrollAccelerator.auto(true);
        assertThat(scroll(accelerator, 1, 0, 3, 6, 9)).containsExactly(1, 1, 1, 1);
    }

    @Test
    void resetsAccelerationOnDirectionChangesAndPauses() {
        var accelerator = WheelScrollAccelerator.auto(true);
        assertThat(scroll(accelerator, 1, 0, 20, 40)).containsExactly(1, 5, 5);
        assertThat(accelerator.next(-1, 60)).isEqualTo(1);
        assertThat(scroll(accelerator, 1, 500, 520)).containsExactly(1, 5);
    }

    @Test
    void carriesFractionalLinesBetweenEvents() {
        var accelerator = WheelScrollAccelerator.auto(true);
        assertThat(scroll(accelerator, 1, 0, 40, 80, 120, 160))
            .containsExactly(1, 2, 3, 2, 3);
    }

    @Test
    void fixedLinesClampToAtLeastOne() {
        assertThat(WheelScrollAccelerator.fixed(0).next(1, 0)).isEqualTo(1);
    }
}
