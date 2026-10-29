package com.pijava.ai.auth;

import java.io.IOException;
import java.time.Instant;

/** Shared pending/slow_down/deadline state machine for device-code flows. */
final class DeviceCodePoller {

    /** One poll outcome. */
    sealed interface PollResult<T> {
        record Complete<T>(T value) implements PollResult<T> {}
        record Pending<T>() implements PollResult<T> {}
        record SlowDown<T>(long serverIntervalSeconds) implements PollResult<T> {}
        record Failed<T>(String message) implements PollResult<T> {}
    }

    @FunctionalInterface
    interface Poll<T> {
        PollResult<T> poll() throws IOException;
    }

    private static final long MIN_INTERVAL_MS = 1_000;
    private static final long SLOW_DOWN_INCREMENT_MS = 5_000;

    private DeviceCodePoller() {}

    /** Poll until completion or the device expiry deadline. */
    static <T> T poll(long intervalSeconds, long expiresInSeconds, Poll<T> poll) throws IOException {
        long deadline = Instant.now().getEpochSecond() + expiresInSeconds;
        long intervalMs = Math.max(MIN_INTERVAL_MS, intervalSeconds * 1_000);
        long slowDowns = 0;
        while (Instant.now().getEpochSecond() < deadline) {
            var result = poll.poll();
            if (result instanceof PollResult.Complete<T> complete) {
                return complete.value();
            }
            if (result instanceof PollResult.Failed<T> failed) {
                throw new IOException(failed.message());
            }
            if (result instanceof PollResult.SlowDown<T> slowDown) {
                slowDowns++;
                intervalMs = slowDown.serverIntervalSeconds() > 0
                    ? Math.max(MIN_INTERVAL_MS, slowDown.serverIntervalSeconds() * 1_000)
                    : Math.max(MIN_INTERVAL_MS, intervalMs + SLOW_DOWN_INCREMENT_MS);
            }
            sleep(intervalMs);
        }
        throw new IOException(slowDowns > 0
            ? "Device flow timed out after one or more slow_down responses"
            : "Device flow timed out. Please restart login.");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
