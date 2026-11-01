package com.pijava.ai.protocol;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/** Pinned append-delta and authoritative-close grammar state machine. */
class GrammarInputBufferTest {

    @Test
    void appendConsumesOnlyDeltaAndCloseConsumesAuthoritativeFullInput() {
        GrammarInputBuffer buffer = new GrammarInputBuffer("input");
        assertThat(buffer.appendDelta("")).isNull();
        assertThat(buffer.appendDelta("a\"b"))
                .isEqualTo("{\"input\":\"a\\\"b");
        assertThat(buffer.input()).isEqualTo("a\"b");
        assertThat(buffer.close("a\"b\\c"))
                .isEqualTo("\\\\c\"}");
        assertThat(buffer.input()).isEqualTo("a\"b\\c");
        assertThat(buffer.closed()).isTrue();
    }

    @Test
    void closeFromInitialUnsentEmitsPrefixOnce() {
        GrammarInputBuffer buffer = new GrammarInputBuffer("query");
        assertThat(buffer.close("a\nb")).isEqualTo("{\"query\":\"a\\nb\"}");
    }

    @Test
    void sameCloseIsIdempotentButChangedCloseFails() {
        GrammarInputBuffer buffer = new GrammarInputBuffer("input");
        buffer.appendDelta("abc");
        assertThat(buffer.close("abcd")).isEqualTo("d\"}");
        assertThat(buffer.close("abcd")).isNull();
        assertThat(buffer.close(null)).isNull();
        assertThatIllegalStateException().isThrownBy(() -> buffer.close("changed"))
                .withMessage("grammar tool input for property \"input\" changed after it was closed");
    }

    @Test
    void anyAppendAfterCloseFailsIncludingEmptyDelta() {
        GrammarInputBuffer buffer = new GrammarInputBuffer("input");
        buffer.close("x");
        assertThatIllegalStateException().isThrownBy(() -> buffer.appendDelta(""))
                .withMessage("grammar tool input for property \"input\" changed after it was closed");
        assertThatIllegalStateException().isThrownBy(() -> buffer.appendDelta("y"))
                .withMessage("grammar tool input for property \"input\" changed after it was closed");
    }

    @Test
    void nonMonotonicCloseFailsWithPinnedMessage() {
        GrammarInputBuffer buffer = new GrammarInputBuffer("input");
        buffer.appendDelta("abc");
        assertThatIllegalStateException().isThrownBy(() -> buffer.close("ab"))
                .withMessage("grammar tool input for property \"input\" changed non-monotonically");
    }
}
