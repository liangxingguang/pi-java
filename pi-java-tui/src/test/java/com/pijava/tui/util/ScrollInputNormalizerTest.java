package com.pijava.tui.util;

import com.pijava.coding.agent.core.Settings;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 alignment design §7.1: ScrollStream state-machine tests (Codex
 * TUI2 PR #8357 semantics): stream splitting, density normalization, wheel
 * immediate flush, trackpad fractional tick flush, bounded acceleration,
 * auto detection, caps, and config fallback.
 */
class ScrollInputNormalizerTest {

    private static ScrollConfig wheel(int eventsPerTick) {
        return new ScrollConfig("wheel", eventsPerTick, 3, 1, 30, 3,
            false, 12, 200, 100);
    }

    private static ScrollConfig wheelInverted() {
        return new ScrollConfig("wheel", 1, 3, 1, 30, 3, true, 12, 200, 100);
    }

    private static ScrollConfig trackpad() {
        return new ScrollConfig("trackpad", 3, 3, 1, 30, 3, false, 12, 200, 100);
    }

    private static ScrollConfig auto(int eventsPerTick) {
        return new ScrollConfig("auto", eventsPerTick, 3, 1, 30, 3,
            false, 12, 200, 100);
    }

    @Test
    void wheelFlushesImmediatelyPerPhysicalNotch() {
        var normalizer = new ScrollInputNormalizer(wheel(1));
        assertThat(normalizer.onEvent(1, 0)).isEqualTo(3);
        assertThat(normalizer.onEvent(1, 1)).isEqualTo(3);
    }

    @Test
    void wheelNormalizesDensityToOneNotch() {
        var three = new ScrollInputNormalizer(wheel(3));
        int sum = 0;
        for (int i = 0; i < 3; i++) {
            sum += three.onEvent(1, i);
        }
        assertThat(sum).isEqualTo(3);

        var nine = new ScrollInputNormalizer(wheel(9));
        int sumNine = 0;
        for (int i = 0; i < 9; i++) {
            sumNine += nine.onEvent(1, i);
        }
        // Acceptance: a physical notch lands within ±1 of wheelLines.
        assertThat(sumNine).isBetween(2, 4);
    }

    @Test
    void wheelInvertReversesDirection() {
        var normalizer = new ScrollInputNormalizer(wheelInverted());
        assertThat(normalizer.onEvent(1, 0)).isEqualTo(-3);
        assertThat(normalizer.onEvent(-1, 1)).isEqualTo(3);
    }

    @Test
    void trackpadAccumulatesFractionalsAndFlushesOnTick() {
        var normalizer = new ScrollInputNormalizer(trackpad());
        assertThat(normalizer.onEvent(1, 0)).isZero();
        assertThat(normalizer.onEvent(1, 1)).isZero();
        assertThat(normalizer.onEvent(1, 2)).isZero();
        // 3 events × ~1/3 row each (with mild acceleration) → 1 row.
        assertThat(normalizer.onTick(16).lines()).isEqualTo(1);
    }

    @Test
    void trackpadFractionalCarriesAcrossStreams() {
        var normalizer = new ScrollInputNormalizer(trackpad());
        normalizer.onEvent(1, 0);
        normalizer.onEvent(1, 1);
        assertThat(normalizer.onTick(200).lines()).isZero(); // idle gap closes stream
        normalizer.onEvent(1, 200);
        normalizer.onEvent(1, 201);
        assertThat(normalizer.onTick(250).lines()).isEqualTo(1);
    }

    @Test
    void trackpadAccelerationIsBounded() {
        var flat = new ScrollConfig("trackpad", 3, 3, 1, 2, 1,
            false, 12, 200, 100);
        var flatNormalizer = new ScrollInputNormalizer(flat);
        for (int i = 0; i < 30; i++) {
            flatNormalizer.onEvent(1, i);
        }
        assertThat(flatNormalizer.onTick(100).lines()).isEqualTo(10);

        var accel = new ScrollConfig("trackpad", 3, 3, 1, 2, 3,
            false, 12, 200, 100);
        var accelNormalizer = new ScrollInputNormalizer(accel);
        for (int i = 0; i < 30; i++) {
            accelNormalizer.onEvent(1, i);
        }
        // 30 events × (1/3 × multiplier), multiplier capped at 3×.
        assertThat(accelNormalizer.onTick(100).lines()).isEqualTo(29);
    }

    @Test
    void idleGapEndsStreamAndDirectionFlipStartsNewStream() {
        var normalizer = new ScrollInputNormalizer(wheel(3));
        assertThat(normalizer.onEvent(1, 0)).isEqualTo(1);
        // 200ms idle (gap = 100ms) → new stream, fresh event accounting.
        assertThat(normalizer.onEvent(1, 200)).isEqualTo(1);
        // Direction flip ends the stream and opens another (scrolling up).
        assertThat(normalizer.onEvent(-1, 300)).isEqualTo(-1);
        assertThat(normalizer.onEvent(-1, 301)).isEqualTo(-1);
    }

    @Test
    void autoPromotesBurstToWheelWithinDetectWindow() {
        var normalizer = new ScrollInputNormalizer(auto(3));
        assertThat(normalizer.onEvent(1, 0)).isZero();
        assertThat(normalizer.onEvent(1, 1)).isZero();
        // Third tick-equivalent event within 12ms → wheel notch, 3 rows.
        assertThat(normalizer.onEvent(1, 2)).isEqualTo(3);
    }

    @Test
    void autoMidStreamTickFlushesInsteadOfWaitingForClose() {
        var normalizer = new ScrollInputNormalizer(auto(1));
        assertThat(normalizer.onEvent(1, 0)).isZero();
        // 1-event-per-tick input: an undecided stream still tracks the finger
        // on every draw tick instead of freezing until the stream closes.
        assertThat(normalizer.onTick(16).lines()).isEqualTo(1);
    }

    @Test
    void autoShortStreamCatchUpDoesNotDoubleCountFlushedRows() {
        var normalizer = new ScrollInputNormalizer(auto(1));
        int total = 0;
        normalizer.onEvent(1, 0);
        normalizer.onEvent(1, 30);
        total += normalizer.onTick(50).lines(); // mid-stream: 2 rows
        normalizer.onEvent(1, 60);
        total += normalizer.onTick(90).lines(); // mid-stream: 1 row
        int close = normalizer.onTick(170).lines(); // idle close within 200ms
        total += close;
        // 3 events × 3 rows (wheel fallback) total; the catch-up only adds the
        // difference between the wheel total and what was already flushed.
        assertThat(total).isEqualTo(9);
        assertThat(close).isEqualTo(6);
    }

    @Test
    void autoSingleEventPerTickFallsBackByStreamDuration() {
        var normalizer = new ScrollInputNormalizer(auto(1));
        assertThat(normalizer.onEvent(1, 0)).isZero();
        // Short stream closed by the idle gap → wheel-like, 3 rows.
        assertThat(normalizer.onTick(200).lines()).isEqualTo(3);
    }

    @Test
    void autoLongStreamClassifiesAsTrackpad() {
        var normalizer = new ScrollInputNormalizer(auto(1));
        for (int i = 0; i < 6; i++) {
            normalizer.onEvent(1, i * 50);
        }
        // Stream outlives wheelLikeMaxDurationMs (200ms) → trackpad; the 6
        // raw events accumulate at 1 line/event (eventsPerTick=1) with mild
        // acceleration, so the tick flush lands at 6 rows.
        assertThat(normalizer.onTick(300).lines()).isEqualTo(6);
    }

    @Test
    void autoDetectedStreamEndsOnDirectionFlip() {
        var normalizer = new ScrollInputNormalizer(auto(3));
        normalizer.onEvent(1, 0);
        normalizer.onEvent(1, 1);
        normalizer.onEvent(1, 2);
        // Burst above was wheel; a flipped direction opens a fresh stream.
        assertThat(normalizer.onEvent(-1, 3)).isZero();
    }

    @Test
    void maxEventsPerStreamCapsAccounting() {
        var normalizer = new ScrollInputNormalizer(wheel(1));
        for (int i = 0; i < ScrollInputNormalizer.MAX_EVENTS_PER_STREAM; i++) {
            normalizer.onEvent(1, i);
        }
        // The 257th event of the stream is ignored.
        assertThat(normalizer.onEvent(1, 256)).isZero();
    }

    @Test
    void maxAccumulatedLinesCapsSingleFlush() {
        var big = new ScrollConfig("trackpad", 1, 3, 1000, 30, 3,
            false, 12, 200, 100);
        var normalizer = new ScrollInputNormalizer(big);
        normalizer.onEvent(1, 0);
        assertThat(normalizer.onTick(16).lines())
            .isEqualTo(ScrollInputNormalizer.MAX_ACCUMULATED_LINES);
    }

    @Test
    void resetClearsStreamState() {
        var normalizer = new ScrollInputNormalizer(wheel(1));
        normalizer.onEvent(1, 0);
        normalizer.reset();
        assertThat(normalizer.onEvent(1, 10)).isEqualTo(3);
    }

    @Test
    void forcedModesIgnoreAutoDetection() {
        var wheelNormalizer = new ScrollInputNormalizer(wheel(1));
        assertThat(wheelNormalizer.onEvent(1, 0)).isEqualTo(3);

        var trackpadNormalizer = new ScrollInputNormalizer(trackpad());
        trackpadNormalizer.onEvent(1, 0);
        trackpadNormalizer.onEvent(1, 1);
        trackpadNormalizer.onEvent(1, 2);
        // No wheel conversion: only the trackpad fractional flush.
        assertThat(trackpadNormalizer.onTick(16).lines()).isEqualTo(1);
    }

    @Test
    void onTickReportsPendingHint() {
        var normalizer = new ScrollInputNormalizer(trackpad());
        normalizer.onEvent(1, 0);
        var pending = normalizer.onTick(16);
        assertThat(pending.lines()).isZero();
        assertThat(pending.nextTickInMs()).isPositive();
        normalizer.reset();
        assertThat(normalizer.onTick(1000).nextTickInMs()).isEqualTo(-1);
    }

    @Test
    void configFromSettingsMapsValues() {
        var settings = new Settings();
        settings.tui = new Settings.Tui(
            "wheel", 9, 5, 2, 40, 4, true, 20, 250, false);
        var config = ScrollConfig.from(settings);

        assertThat(config.mode()).isEqualTo("wheel");
        assertThat(config.eventsPerTick()).isEqualTo(9);
        assertThat(config.wheelLines()).isEqualTo(5);
        assertThat(config.trackpadLines()).isEqualTo(2);
        assertThat(config.trackpadAccelEvents()).isEqualTo(40);
        assertThat(config.trackpadAccelMax()).isEqualTo(4);
        assertThat(config.invert()).isTrue();
        assertThat(config.wheelTickDetectMaxMs()).isEqualTo(20);
        assertThat(config.wheelLikeMaxDurationMs()).isEqualTo(250);
        assertThat(config.streamGapMs()).isEqualTo(ScrollConfig.DEFAULT_STREAM_GAP_MS);
    }

    @Test
    void invalidConfigFallsBackToDefaults() {
        var settings = new Settings();
        settings.tui = new Settings.Tui(
            "bogus", 0, -1, null, null, null, null, null, null, null);
        var config = ScrollConfig.from(settings);
        assertThat(config).isEqualTo(ScrollConfig.defaults());
        assertThat(ScrollConfig.from(null)).isEqualTo(ScrollConfig.defaults());
    }
}
