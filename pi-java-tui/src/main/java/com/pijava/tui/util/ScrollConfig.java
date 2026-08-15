package com.pijava.tui.util;

import com.pijava.coding.agent.core.Settings;

/**
 * Scroll normalization configuration (Phase 3 alignment design §4.2), a
 * port of Codex TUI2's {@code tui.scroll_*} knobs (PR #8357).
 *
 * <p>Terminals encode wheel and trackpad input as discrete SCROLL events
 * with wildly different densities (one physical wheel notch = 1/3/9 raw
 * events depending on the terminal), so the raw stream is normalized to
 * tick-equivalent rows before it drives the chat viewport.</p>
 *
 * @param mode auto | wheel | trackpad; {@code auto} starts conservatively
 *             as trackpad and promotes to wheel on burst evidence
 * @param eventsPerTick raw scroll events per physical wheel notch
 * @param wheelLines rows scrolled per physical wheel notch
 * @param trackpadLines rows scrolled per trackpad event (pre-acceleration)
 * @param trackpadAccelEvents events before acceleration reaches 2×
 * @param trackpadAccelMax maximum acceleration multiplier
 * @param invert flip scroll direction
 * @param wheelTickDetectMaxMs burst window that proves a stream is wheel-like
 * @param wheelLikeMaxDurationMs maximum duration of a wheel-like burst
 * @param streamGapMs idle gap that ends a scroll stream
 */
public record ScrollConfig(
    String mode,
    int eventsPerTick,
    int wheelLines,
    int trackpadLines,
    int trackpadAccelEvents,
    int trackpadAccelMax,
    boolean invert,
    int wheelTickDetectMaxMs,
    int wheelLikeMaxDurationMs,
    int streamGapMs
) {
    /** Stream idle gap used when nothing else is configured (Codex default). */
    static final int DEFAULT_STREAM_GAP_MS = 100;

    /**
     * The recommended defaults (Codex TUI2 measured values: 3 lines per
     * wheel notch, 3 events per tick, 1 trackpad line per event).
     *
     * @return a default configuration
     */
    public static ScrollConfig defaults() {
        return new ScrollConfig(
            "auto", 3, 3, 1, 30, 3, false, 12, 200, DEFAULT_STREAM_GAP_MS);
    }

    /**
     * Map a {@link Settings} TUI section to a normalized scroll config;
     * null settings, a missing {@code tui} section, and invalid values all
     * fall back to the defaults.
     *
     * @param settings the settings root (may be null)
     * @return the effective scroll configuration
     */
    public static ScrollConfig from(Settings settings) {
        return from(settings, defaults().eventsPerTick());
    }

    /**
     * Map a {@link Settings} TUI section to a normalized scroll config with a
     * platform-specific {@code eventsPerTick} default. Windows Terminal /
     * ConPTY deliver about one raw event per wheel notch (like VS Code), so
     * pi-java falls back to 1 there instead of the 3 measured on Warp/Ghostty.
     *
     * @param settings             the settings root (may be null)
     * @param defaultEventsPerTick per-notch event density to assume when the
     *                             settings omit {@code scroll_events_per_tick}
     * @return the effective scroll configuration
     */
    public static ScrollConfig from(Settings settings, int defaultEventsPerTick) {
        var defaults = defaults();
        if (settings == null || settings.tui == null) {
            return new ScrollConfig(
                defaults.mode(), defaultEventsPerTick, defaults.wheelLines(),
                defaults.trackpadLines(), defaults.trackpadAccelEvents(),
                defaults.trackpadAccelMax(), defaults.invert(),
                defaults.wheelTickDetectMaxMs(), defaults.wheelLikeMaxDurationMs(),
                defaults.streamGapMs());
        }
        var tui = settings.tui;
        String mode = isValidMode(tui.scrollMode()) ? tui.scrollMode() : defaults.mode();
        return new ScrollConfig(
            mode,
            intOr(tui.scrollEventsPerTick(), defaultEventsPerTick, 1, 64),
            intOr(tui.scrollWheelLines(), defaults.wheelLines(), 1, 128),
            intOr(tui.scrollTrackpadLines(), defaults.trackpadLines(), 1, 128),
            intOr(tui.scrollTrackpadAccelEvents(), defaults.trackpadAccelEvents(), 1, 10_000),
            intOr(tui.scrollTrackpadAccelMax(), defaults.trackpadAccelMax(), 1, 64),
            tui.scrollInvert() != null && tui.scrollInvert(),
            intOr(tui.scrollWheelTickDetectMaxMs(), defaults.wheelTickDetectMaxMs(), 1, 10_000),
            intOr(tui.scrollWheelLikeMaxDurationMs(), defaults.wheelLikeMaxDurationMs(), 1, 10_000),
            defaults.streamGapMs());
    }

    /** Whether the mode forces wheel semantics. */
    public boolean wheelLike() {
        return "wheel".equals(mode);
    }

    /** Whether the mode forces trackpad semantics. */
    public boolean trackpadLike() {
        return "trackpad".equals(mode);
    }

    private static boolean isValidMode(String mode) {
        return "auto".equals(mode) || "wheel".equals(mode) || "trackpad".equals(mode);
    }

    private static int intOr(Integer value, int fallback, int min, int max) {
        return value == null || value < min || value > max ? fallback : value;
    }
}
