package com.pijava.ai.stream;

import org.junit.jupiter.api.Test;

import com.pijava.ai.AbortedException;
import com.pijava.ai.message.StopReason;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * pi {@code api/lazy.ts:80-83} 的对位：中止在场 ⇒ {@code aborted}；真实失败 ⇒ {@code error}。
 */
class StreamFailuresTest {

    @Test
    void abortExceptionMapsToAborted() {
        assertThat(StreamFailures.reasonFor(new AbortedException()))
            .isEqualTo(StopReason.ABORTED);
    }

    @Test
    void interruptMapsToAborted() {
        assertThat(StreamFailures.reasonFor(new InterruptedException()))
            .isEqualTo(StopReason.ABORTED);
    }

    @Test
    void plainFailureStaysError() {
        assertThat(StreamFailures.reasonFor(new IllegalStateException("boom")))
            .isEqualTo(StopReason.ERROR);
    }
}
