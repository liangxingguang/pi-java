package com.pijava.tui.util;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** E2-32：OSC 9;4 进度序列、保活与启用门（pi terminal.ts:618-633）。 */
class TerminalProgressReporterTest {

    @Test
    void startWritesActiveAndStopWritesClear() {
        var sink = new ArrayList<String>();
        var enabled = new AtomicBoolean(true);
        try (var reporter = new TerminalProgressReporter(sink::add, enabled::get, 1000,
                Executors.newSingleThreadScheduledExecutor())) {
            reporter.start();
            assertThat(sink).containsExactly(TerminalProgressReporter.ACTIVE_SEQUENCE);
            assertThat(reporter.active()).isTrue();
            reporter.stop();
            assertThat(sink).containsExactly(
                TerminalProgressReporter.ACTIVE_SEQUENCE,
                TerminalProgressReporter.CLEAR_SEQUENCE);
            assertThat(reporter.active()).isFalse();
            reporter.stop();
            assertThat(sink).hasSize(2);
        }
    }

    @Test
    void disabledSettingProducesNothing() {
        var sink = new ArrayList<String>();
        try (var reporter = new TerminalProgressReporter(sink::add, () -> false, 1000,
                Executors.newSingleThreadScheduledExecutor())) {
            reporter.start();
            reporter.stop();
            assertThat(sink).isEmpty();
            assertThat(reporter.active()).isFalse();
        }
    }

    @Test
    void keepaliveRepeatsWhileActive() throws Exception {
        var sink = new ArrayList<String>();
        var latch = new CountDownLatch(3);
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        try (var reporter = new TerminalProgressReporter(sequence -> {
            synchronized (sink) {
                sink.add(sequence);
            }
            latch.countDown();
        }, () -> true, 20, scheduler)) {
            reporter.start();
            assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
            reporter.stop();
            synchronized (sink) {
                assertThat(sink.subList(0, 3))
                    .containsOnly(TerminalProgressReporter.ACTIVE_SEQUENCE);
                assertThat(sink.get(sink.size() - 1))
                    .isEqualTo(TerminalProgressReporter.CLEAR_SEQUENCE);
            }
        } finally {
            scheduler.shutdownNow();
        }
    }
}
