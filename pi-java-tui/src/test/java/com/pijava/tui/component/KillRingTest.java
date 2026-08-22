package com.pijava.tui.component;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * KillRing ring-buffer semantics (pi {@code tui/kill-ring.ts}).
 */
class KillRingTest {

    @Test
    void pushAddsEntriesInOrder() {
        var ring = new KillRing();
        ring.push("a", false, false);
        ring.push("b", false, false);
        assertThat(ring.peek()).isEqualTo("b");
        assertThat(ring.length()).isEqualTo(2);
    }

    @Test
    void consecutiveKillsAccumulateAppending() {
        var ring = new KillRing();
        ring.push("a", false, false);
        ring.push("b", false, true);
        assertThat(ring.peek()).isEqualTo("ab");
        assertThat(ring.length()).isEqualTo(1);
    }

    @Test
    void accumulatePrependReversesOrder() {
        var ring = new KillRing();
        ring.push("word", true, false);
        ring.push(" ", true, true);
        assertThat(ring.peek()).isEqualTo(" word");
    }

    @Test
    void rotateCyclesMostRecentToFront() {
        var ring = new KillRing();
        ring.push("a", false, false);
        ring.push("b", false, false);
        ring.rotate();
        assertThat(ring.peek()).isEqualTo("a");
        assertThat(ring.length()).isEqualTo(2);
    }

    @Test
    void emptyTextIsIgnored() {
        var ring = new KillRing();
        ring.push("", false, false);
        ring.push("x", false, false);
        assertThat(ring.length()).isEqualTo(1);
    }

    @Test
    void peekOnEmptyRingIsNull() {
        assertThat(new KillRing().peek()).isNull();
    }
}
