package com.pijava.tui.util;

/**
 * Normalizes raw terminal wheel/trackpad SCROLL events into row scrolls
 * (Phase 3 alignment design §4.1), a port of Codex TUI2 PR #8357.
 *
 * <p>Terminals encode both input types as discrete SCROLL_UP/DOWN events,
 * but density differs enormously (a physical wheel notch can arrive as
 * 1/3/9 raw events). The normalizer therefore treats input as a short
 * <em>event stream</em>: the first event opens a stream, an idle gap
 * ({@link ScrollConfig#streamGapMs()}) or a direction flip closes it, and
 * each raw event is converted into tick-equivalent rows. Wheel-like input
 * flushes immediately; trackpad-like input accumulates fractional rows and
 * flushes on the draw tick with bounded acceleration.</p>
 *
 * <p>Pure state machine: no I/O, no TamboUI dependency — fully unit-testable.</p>
 */
public final class ScrollInputNormalizer {

    /** Hard cap on events considered within one stream. */
    public static final int MAX_EVENTS_PER_STREAM = 256;

    /** Hard cap on rows flushed from one tick (or one wheel burst). */
    public static final int MAX_ACCUMULATED_LINES = 256;

    /** Draw-tick hint returned while fractional rows are pending. */
    private static final long TICK_HINT_MS = 16;

    private final ScrollConfig config;
    private StreamKind kind = StreamKind.UNKNOWN;
    private long streamStartMs = Long.MIN_VALUE;
    private long lastEventMs = Long.MIN_VALUE;
    private int eventsInStream;
    private double fractionalLines;
    private int lastDirection;
    /** Rows flushed since the current stream opened (catch-up subtraction). */
    private double flushedDuringStream;

    /**
     * Creates a normalizer with the given configuration.
     *
     * @param config the scroll configuration (null falls back to defaults)
     */
    public ScrollInputNormalizer(ScrollConfig config) {
        this.config = config != null ? config : ScrollConfig.defaults();
    }

    /** Creates a normalizer with the default configuration. */
    public ScrollInputNormalizer() {
        this(ScrollConfig.defaults());
    }

    /**
     * Delivers one raw scroll event.
     *
     * @param direction +1 scrolls down, -1 scrolls up
     * @param nowMs    monotonic-ish wall-clock time of the event
     * @return rows to scroll immediately (0 = defer to {@link #onTick})
     */
    public int onEvent(int direction, long nowMs) {
        if (config.wheelLike()) {
            return wheelEvent(direction, nowMs);
        }
        if (config.trackpadLike()) {
            return trackpadEvent(direction, nowMs);
        }
        return autoEvent(direction, nowMs);
    }

    /**
     * Called once per draw tick: flushes accumulated trackpad fractionals,
     * closes idle streams, and classifies undecided auto streams.
     *
     * @param nowMs current wall-clock time
     * @return rows to scroll this tick plus a next-tick hint
     */
    public ScrollUpdate onTick(long nowMs) {
        int lines = 0;
        if (streamStartMs != Long.MIN_VALUE) {
            if (nowMs - lastEventMs > config.streamGapMs()) {
                lines += closeStream(nowMs);
            } else if (kind == StreamKind.UNKNOWN
                    && nowMs - streamStartMs > config.wheelLikeMaxDurationMs()) {
                // A stream that outlives any wheel burst is trackpad input.
                kind = StreamKind.TRACKPAD;
            }
        }
        // Flush accumulated rows every tick — even for undecided streams — so
        // scrolling tracks the fingers instead of freezing until the stream
        // closes. The short-stream wheel fallback subtracts what was already
        // flushed, so there is no catch-up double count.
        lines += flush();
        long nextTick = Math.abs(fractionalLines) > 0 ? TICK_HINT_MS : -1;
        return new ScrollUpdate(lines, nextTick);
    }

    /** Resets all state (session switch / config change). */
    public void reset() {
        kind = StreamKind.UNKNOWN;
        streamStartMs = Long.MIN_VALUE;
        lastEventMs = Long.MIN_VALUE;
        eventsInStream = 0;
        fractionalLines = 0;
        lastDirection = 0;
        flushedDuringStream = 0;
    }

    private int wheelEvent(int direction, long nowMs) {
        int dir = applyInvert(direction);
        startStreamIfNeeded(dir, nowMs);
        kind = StreamKind.WHEEL;
        if (!countEvent(nowMs)) {
            return 0;
        }
        // Each raw event is one eventsPerTick-th of a physical notch; the
        // integer part is flushed immediately so a notch lands wheelLines
        // rows (within ±1 rounding) regardless of terminal density.
        fractionalLines += dir * (double) config.wheelLines() / config.eventsPerTick();
        return flush();
    }

    private int trackpadEvent(int direction, long nowMs) {
        int dir = applyInvert(direction);
        startStreamIfNeeded(dir, nowMs);
        kind = StreamKind.TRACKPAD;
        if (!countEvent(nowMs)) {
            return 0;
        }
        fractionalLines += dir * perTrackpadEvent();
        return 0;
    }

    private int autoEvent(int direction, long nowMs) {
        int dir = applyInvert(direction);
        startStreamIfNeeded(dir, nowMs);
        if (!countEvent(nowMs)) {
            return 0;
        }
        if (kind == StreamKind.WHEEL) {
            // Already proven wheel-like: keep wheel semantics for the rest of
            // the burst, so a physical notch lands wheelLines rows regardless
            // of how many raw events it contains.
            fractionalLines += dir * (double) config.wheelLines() / config.eventsPerTick();
            return flush();
        }
        // Conservative start: accumulate at trackpad granularity until the
        // stream proves itself wheel-like.
        fractionalLines += dir * perTrackpadEvent();
        if (kind == StreamKind.UNKNOWN
                && config.eventsPerTick() > 1
                && eventsInStream >= config.eventsPerTick()
                && nowMs - streamStartMs <= config.wheelTickDetectMaxMs()) {
            promoteToWheel();
            return flush();
        }
        return 0;
    }

    /**
     * Converts the current stream to wheel semantics and recomputes the
     * accumulated fractional rows from the wheel model, so a detected notch
     * yields wheelLines rows no matter how many raw events it contained.
     */
    private void promoteToWheel() {
        kind = StreamKind.WHEEL;
        fractionalLines = lastDirection
            * (double) config.wheelLines() * eventsInStream / config.eventsPerTick();
    }

    private double perTrackpadEvent() {
        double base = (double) config.trackpadLines()
            / Math.min(config.eventsPerTick(), 3);
        double multiplier = 1.0 + eventsInStream / (double) config.trackpadAccelEvents();
        return base * Math.min(multiplier, config.trackpadAccelMax());
    }

    private int applyInvert(int direction) {
        return config.invert() ? -direction : direction;
    }

    private boolean countEvent(long nowMs) {
        if (eventsInStream >= MAX_EVENTS_PER_STREAM) {
            lastEventMs = nowMs;
            return false;
        }
        eventsInStream++;
        lastEventMs = nowMs;
        return true;
    }

    private void startStreamIfNeeded(int dir, long nowMs) {
        if (streamStartMs != Long.MIN_VALUE
                && (nowMs - lastEventMs > config.streamGapMs()
                    || (lastDirection != 0 && dir != lastDirection))) {
            closeStream(nowMs);
        }
        if (streamStartMs == Long.MIN_VALUE) {
            streamStartMs = nowMs;
            lastEventMs = nowMs;
            lastDirection = dir;
            eventsInStream = 0;
            flushedDuringStream = 0;
            kind = config.wheelLike() ? StreamKind.WHEEL
                : config.trackpadLike() ? StreamKind.TRACKPAD
                : StreamKind.UNKNOWN;
        }
    }

    /**
     * Ends the current stream. An undecided auto stream that lasted no
     * longer than a wheel burst is classified as wheel (this is the fallback
     * for 1-event-per-tick terminals); fractional rows carry over to the
     * next stream either way.
     *
     * @param nowMs current wall-clock time
     * @return rows to scroll from the classification decision
     */
    private int closeStream(long nowMs) {
        int lines = 0;
        if (kind == StreamKind.UNKNOWN && eventsInStream > 0) {
            if (nowMs - streamStartMs <= config.wheelLikeMaxDurationMs()) {
                // Short undecided stream → wheel notch (the fallback for
                // 1-event-per-tick terminals). Recompute the wheel-equivalent
                // total and subtract the rows already flushed conservatively
                // during the stream, so the catch-up never double-counts.
                double wheelTotal = lastDirection
                    * (double) config.wheelLines() * eventsInStream / config.eventsPerTick();
                fractionalLines = wheelTotal - flushedDuringStream;
            }
        }
        // Flush the integer part of whatever the stream accumulated; any
        // fractional remainder carries over to the next stream.
        lines += flush();
        streamStartMs = Long.MIN_VALUE;
        lastEventMs = Long.MIN_VALUE;
        eventsInStream = 0;
        lastDirection = 0;
        kind = StreamKind.UNKNOWN;
        flushedDuringStream = 0;
        return lines;
    }

    /** Flushes the integer part of the fractional accumulator (bounded). */
    private int flush() {
        if (fractionalLines == 0) {
            return 0;
        }
        int lines = (int) fractionalLines;
        fractionalLines -= lines;
        if (lines > MAX_ACCUMULATED_LINES) {
            lines = MAX_ACCUMULATED_LINES;
            fractionalLines = 0;
        } else if (lines < -MAX_ACCUMULATED_LINES) {
            lines = -MAX_ACCUMULATED_LINES;
            fractionalLines = 0;
        }
        if (fractionalLines > MAX_ACCUMULATED_LINES) {
            fractionalLines = MAX_ACCUMULATED_LINES;
        } else if (fractionalLines < -MAX_ACCUMULATED_LINES) {
            fractionalLines = -MAX_ACCUMULATED_LINES;
        }
        flushedDuringStream += Math.abs(lines);
        return lines;
    }

    private enum StreamKind {
        /** Stream not yet classified (auto mode). */
        UNKNOWN,
        /** Wheel-like: flush immediately, wheelLines per notch. */
        WHEEL,
        /** Trackpad-like: fractional accumulation + tick flush. */
        TRACKPAD
    }
}
