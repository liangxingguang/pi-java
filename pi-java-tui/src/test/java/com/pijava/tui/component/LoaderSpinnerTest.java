package com.pijava.tui.component;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L7 W1（docs/22 §6，C5）：pi {@code loader.ts} 的帧计算逐字对位——
 * 空帧 ⇒ 无符号、单帧 ⇒ 静态、多帧 ⇒ 取模推进（时间驱动无漂移）。
 */
class LoaderSpinnerTest {

    private static final long START = 1_000_000_000L;   // 任意起点（nanoTime 基）

    @Test
    void defaultFramesAdvanceByTheInterval() {
        var frames = LoaderSpinner.DEFAULT_FRAMES;
        // 0ms ⇒ 帧 0；80ms ⇒ 帧 1；800ms ⇒ 帧 0（10 帧 取模回绕）。
        assertThat(LoaderSpinner.frameAt(frames, 80L, START, START))
            .isEqualTo("⠋");
        assertThat(LoaderSpinner.frameAt(frames, 80L, START, START + 80L * 1_000_000L))
            .isEqualTo("⠙");
        assertThat(LoaderSpinner.frameAt(frames, 80L, START, START + 800L * 1_000_000L))
            .isEqualTo("⠋");
    }

    @Test
    void nonPositiveIntervalFallsBackToTheDefault() {
        var frames = LoaderSpinner.DEFAULT_FRAMES;
        // pi setIndicator：intervalMs <= 0 ⇒ DEFAULT_INTERVAL_MS（80ms）。
        assertThat(LoaderSpinner.frameAt(frames, 0L, START, START + 80L * 1_000_000L))
            .isEqualTo("⠙");
    }

    @Test
    void emptyFramesHideTheSpinnerSymbol() {
        assertThat(LoaderSpinner.frameAt(List.of(), 80L, START, START + 1_000_000L))
            .isEmpty();
        assertThat(LoaderSpinner.frameAt(null, 80L, START, START + 1_000_000L))
            .isEmpty();
    }

    @Test
    void singleFrameIsStatic() {
        // pi：frames.length <= 1 ⇒ 无定时器，恒取首帧（custom frames verbatim）。
        assertThat(LoaderSpinner.frameAt(List.of("●"), 80L, START, START + 999_999_999L))
            .isEqualTo("●");
    }
}
