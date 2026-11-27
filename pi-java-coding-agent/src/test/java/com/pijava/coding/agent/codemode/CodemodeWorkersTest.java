package com.pijava.coding.agent.codemode;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.pijava.ai.AbortSignal;

/** Tests for the process-wide worker admission gates. */
class CodemodeWorkersTest {
    @Test
    void waitsForFreeSlotThenTimesOut() {
        int max = CodemodeWorkers.maxWorkers();
        for (int i = 0; i < max; i++) {
            assertThat(CodemodeWorkers.reserve(null, 1_000))
                .isEqualTo(CodemodeWorkers.Admission.RESERVED);
        }
        try {
            assertThat(CodemodeWorkers.reserve(null, 50))
                .isEqualTo(CodemodeWorkers.Admission.TIMEOUT);
        } finally {
            for (int i = 0; i < max; i++) {
                CodemodeWorkers.release();
            }
        }
        assertThat(CodemodeWorkers.reserve(null, 50))
            .isEqualTo(CodemodeWorkers.Admission.RESERVED);
        CodemodeWorkers.release();
    }

    @Test
    void abortWakesWaitingScript() throws Exception {
        int max = CodemodeWorkers.maxWorkers();
        for (int i = 0; i < max; i++) {
            CodemodeWorkers.reserve(null, 1_000);
        }
        AbortSignal signal = AbortSignal.create();
        AtomicReference<CodemodeWorkers.Admission> outcome = new AtomicReference<>();
        Thread waiter = new Thread(() -> outcome.set(CodemodeWorkers.reserve(signal, 30_000)));
        try {
            waiter.start();
            Thread.sleep(100);
            signal.abort();
            waiter.join(2_000);
            assertThat(waiter.isAlive()).isFalse();
            assertThat(outcome.get()).isEqualTo(CodemodeWorkers.Admission.ABORTED);
        } finally {
            for (int i = 0; i < max; i++) {
                CodemodeWorkers.release();
            }
        }
    }

    @Test
    void alreadyAbortedSignalDoesNotWait() {
        AbortSignal signal = AbortSignal.create();
        signal.abort();
        assertThat(CodemodeWorkers.reserve(signal, 30_000))
            .isEqualTo(CodemodeWorkers.Admission.ABORTED);
    }
}
