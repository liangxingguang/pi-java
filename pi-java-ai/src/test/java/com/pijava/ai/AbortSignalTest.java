package com.pijava.ai;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * pi {@code AbortSignal.throwIfAborted()} 的 Java 对位（docs/11 #31）。
 */
class AbortSignalTest {

    @Test
    void throwIfAbortedThrowsOnlyAfterAbort() {
        var signal = AbortSignal.create();

        assertThatCode(signal::throwIfAborted).doesNotThrowAnyException();

        signal.abort();

        assertThatThrownBy(signal::throwIfAborted)
            .isInstanceOf(AbortedException.class)
            .hasMessage("This operation was aborted");
    }
}
