package com.pijava.tui.util;

/**
 * Result of one normalization step (Phase 3 alignment design §4.3): the rows
 * to scroll now, plus a hint for when the next tick should run (informational;
 * the app already ticks every frame).
 *
 * @param lines rows to scroll (positive = down, negative = up, 0 = none)
 * @param nextTickInMs when a follow-up tick is useful, or -1 if none
 */
public record ScrollUpdate(int lines, long nextTickInMs) {

    /** A flush that must be applied immediately. */
    static ScrollUpdate immediate(int lines) {
        return new ScrollUpdate(lines, 0);
    }

    /** No scroll work pending. */
    static ScrollUpdate none() {
        return new ScrollUpdate(0, -1);
    }
}
