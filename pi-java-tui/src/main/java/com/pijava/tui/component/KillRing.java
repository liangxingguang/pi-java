package com.pijava.tui.component;

import java.util.ArrayList;
import java.util.List;

/**
 * Emacs-style kill/yank ring buffer (pi {@code tui/kill-ring.ts}).
 *
 * <p>Tracks killed (deleted) text entries. Consecutive kills accumulate into a
 * single entry. Supports yank (paste most recent) and yank-pop (cycle through
 * older entries).</p>
 */
final class KillRing {

    private final List<String> ring = new ArrayList<>();

    /**
     * Add killed text to the ring.
     *
     * @param text       the killed text ({@code ""}/{@code null} is ignored)
     * @param prepend    when accumulating, prepend (backward kill) or append (forward kill)
     * @param accumulate merge with the most recent entry instead of creating a new one
     */
    void push(String text, boolean prepend, boolean accumulate) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (accumulate && !ring.isEmpty()) {
            String last = ring.remove(ring.size() - 1);
            ring.add(prepend ? text + last : last + text);
        } else {
            ring.add(text);
        }
    }

    /** Most recent entry without modifying the ring ({@code null} when empty). */
    String peek() {
        return ring.isEmpty() ? null : ring.get(ring.size() - 1);
    }

    /** Move the most recent entry to the front (for yank-pop cycling). */
    void rotate() {
        if (ring.size() > 1) {
            ring.add(0, ring.remove(ring.size() - 1));
        }
    }

    int length() {
        return ring.size();
    }
}
