package com.pijava.tui.util;

/**
 * Converts wheel events into line counts, a port of pi
 * {@code packages/tui/src/wheel-scroll.ts} (anchor {@code a00a5048d}).
 *
 * <p>In auto mode on terminals that do not accelerate wheel input, the count
 * follows event velocity: an isolated notch moves one line, while a fast spin
 * moves up to six lines per event. macOS terminals that receive OS-accelerated
 * trackpad deltas keep one line per event, so they pass
 * {@code accelerate = false}.</p>
 */
public final class WheelScrollAccelerator {

    /** Events closer than this belong to one physical notch and do not accelerate. */
    static final long BURST_GAP_MS = 5;
    /** A pause longer than this ends a scroll gesture. */
    static final long GESTURE_GAP_MS = 200;
    /** Average event gap that maps to one line per event. */
    static final long REFERENCE_GAP_MS = 100;
    /** Upper bound for auto acceleration. */
    static final int MAX_AUTO_LINES = 6;

    /** Fixed lines per event, or {@code null} for auto acceleration. */
    private Integer lines;
    private final boolean accelerate;
    private Long lastTime;
    private int lastDirection;
    private Double averageGap;
    private double carry;

    /**
     * Creates an accelerator.
     *
     * @param lines      fixed lines per event, or {@code null} for auto
     * @param accelerate whether auto mode follows event velocity
     */
    public WheelScrollAccelerator(Integer lines, boolean accelerate) {
        this.lines = lines;
        this.accelerate = accelerate;
    }

    /** Creates a fixed-line accelerator (pi numeric {@code wheelScrollLines}). */
    public static WheelScrollAccelerator fixed(int lines) {
        return new WheelScrollAccelerator(Math.max(1, lines), true);
    }

    /** Creates an auto accelerator; see {@link #defaultAccelerate()} for the platform default. */
    public static WheelScrollAccelerator auto(boolean accelerate) {
        return new WheelScrollAccelerator(null, accelerate);
    }

    /** Replaces the line setting and resets acceleration state (pi {@code setLines}). */
    public void setLines(Integer lines) {
        this.lines = lines;
        reset();
    }

    /**
     * Returns the positive line count for a wheel event in {@code direction}
     * at {@code nowMs} (milliseconds).
     *
     * @param direction -1 up, +1 down
     * @param nowMs     event time in milliseconds
     * @return positive rows to scroll
     */
    public int next(int direction, long nowMs) {
        if (lines != null) {
            return Math.max(1, lines);
        }
        if (!accelerate) {
            return 1;
        }
        long gap = lastTime == null ? Long.MAX_VALUE : nowMs - lastTime;
        boolean sameGesture = lastTime != null
            && direction == lastDirection && gap <= GESTURE_GAP_MS;
        lastTime = nowMs;
        lastDirection = direction;
        if (!sameGesture) {
            averageGap = null;
            carry = 0;
            return 1;
        }
        if (gap < BURST_GAP_MS) {
            return 1;
        }
        averageGap = averageGap == null ? gap : (averageGap + gap) / 2.0;
        double scaled = Math.min(MAX_AUTO_LINES,
            Math.max(1.0, REFERENCE_GAP_MS / averageGap)) + carry;
        int whole = (int) Math.floor(scaled);
        carry = scaled - whole;
        return whole;
    }

    private void reset() {
        lastTime = null;
        lastDirection = 0;
        averageGap = null;
        carry = 0;
    }

    /**
     * Whether this platform should accelerate wheel input: pi returns true
     * unless the terminal is a local macOS one (the OS already accelerated the
     * deltas). SSH sessions are treated as unknown-client platforms.
     *
     * @return true when auto mode should follow event velocity
     */
    public static boolean defaultAccelerate() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        boolean macOS = os.contains("mac");
        boolean ssh = System.getenv("SSH_CONNECTION") != null
            || System.getenv("SSH_CLIENT") != null
            || System.getenv("SSH_TTY") != null;
        return !(macOS && !ssh);
    }
}
