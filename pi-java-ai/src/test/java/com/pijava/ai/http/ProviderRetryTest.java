package com.pijava.ai.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import com.pijava.ai.http.ProviderRetry.Options;
import com.pijava.ai.http.ProviderRetry.ProviderFailure;

/**
 * 平移 pi {@code packages/ai/test/provider-retry.test.ts} 的断言（abort 用例
 * 随 R5 不移植；fake timers 换成记录式 Sleeper）。
 */
class ProviderRetryTest {

    /** 车道 SDK 异常在测试里的替身：经 adaptor 投影成 ProviderFailure。 */
    static final class FakeSdkException extends RuntimeException {
        private final ProviderFailure failure;

        FakeSdkException(ProviderFailure failure) {
            super(failure.message(), failure.cause());
            this.failure = failure;
        }

        ProviderFailure failure() {
            return failure;
        }
    }

    private static final Function<Throwable, ProviderFailure> ADAPT =
            t -> t instanceof FakeSdkException f ? f.failure() : null;

    private static ProviderFailure failure(Integer status, Map<String, String> headers) {
        return new ProviderFailure("Provider error: " + status, status, toMultiMap(headers), null);
    }

    private static Map<String, List<String>> toMultiMap(Map<String, String> headers) {
        var map = new java.util.TreeMap<String, List<String>>(String.CASE_INSENSITIVE_ORDER);
        headers.forEach((k, v) -> map.put(k, List.of(v)));
        return map;
    }

    private static String runWithRecordedSleep(
            Supplier<String> request, Options options, List<Long> slept) {
        return ProviderRetry.retry(request, ADAPT, options, ms -> slept.add(ms));
    }

    @Test
    void retriesRetryableProviderErrors() {
        var calls = new AtomicInteger();
        var slept = new ArrayList<Long>();
        Supplier<String> request = () -> {
            if (calls.getAndIncrement() == 0) {
                throw new FakeSdkException(failure(429, Map.of("retry-after-ms", "1000")));
            }
            return "ok";
        };

        String result = runWithRecordedSleep(request, new Options(1, 60_000L), slept);

        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(2);
        assertThat(slept).containsExactly(1000L);
    }

    @Test
    void doesNotRetryErrorsMarkedNonRetryable() {
        var error = new FakeSdkException(failure(429, Map.of("x-should-retry", "false")));
        var calls = new AtomicInteger();
        Supplier<String> request = () -> {
            calls.incrementAndGet();
            throw error;
        };

        assertThatThrownBy(() -> runWithRecordedSleep(
                request, new Options(2, 60_000L), new ArrayList<>()))
                .isSameAs(error);
        assertThat(calls).hasValue(1);
    }

    @Test
    void rejectsDelayAboveTheLimit() {
        var error = new FakeSdkException(failure(429, Map.of("retry-after", "277403")));
        var calls = new AtomicInteger();
        Supplier<String> request = () -> {
            calls.incrementAndGet();
            throw error;
        };

        assertThatThrownBy(() -> runWithRecordedSleep(
                request, new Options(1, 1000L), new ArrayList<>()))
                .isInstanceOf(ProviderRetry.RetryDelayTooLongException.class)
                .hasMessage("Server requested 277403s retry delay (max: 1s). "
                        + "Provider error: 429");
        assertThat(calls).hasValue(1);
    }

    @Test
    void allowsDisablingTheCap() {
        var calls = new AtomicInteger();
        var slept = new ArrayList<Long>();
        Supplier<String> request = () -> {
            if (calls.getAndIncrement() == 0) {
                throw new FakeSdkException(failure(429, Map.of("retry-after", "2")));
            }
            return "ok";
        };

        String result = runWithRecordedSleep(request, new Options(1, 0L), slept);

        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(2);
        assertThat(slept).containsExactly(2000L);
    }

    @Test
    void retriesErrorsWithNoStatus() {
        // pi: status === undefined ⇒ retryable（IO 错误；RE-4 钉死该分支）。
        var calls = new AtomicInteger();
        var error = new FakeSdkException(
                new ProviderFailure("connection reset", null, null, null));
        Supplier<String> request = () -> {
            if (calls.getAndIncrement() == 0) {
                throw error;
            }
            return "ok";
        };

        String result = runWithRecordedSleep(
                request, new Options(1, 60_000L), new ArrayList<>());

        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(2);
    }

    @Test
    void doesNotRetryNonProviderErrors() {
        var calls = new AtomicInteger();
        Supplier<String> request = () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("boom");
        };

        assertThatThrownBy(() -> runWithRecordedSleep(
                request, new Options(2, 60_000L), new ArrayList<>()))
                .isInstanceOf(IllegalStateException.class).hasMessage("boom");
        assertThat(calls).hasValue(1);
    }

    @Test
    void doesNotRetryStatusOutsideTheSet() {
        var error = new FakeSdkException(failure(400, Map.of()));
        var calls = new AtomicInteger();
        Supplier<String> request = () -> {
            calls.incrementAndGet();
            throw error;
        };

        assertThatThrownBy(() -> runWithRecordedSleep(
                request, new Options(2, 60_000L), new ArrayList<>()))
                .isSameAs(error);
        assertThat(calls).hasValue(1);
    }

    @Test
    void headerShouldRetryTrueForcesRetryRegardlessOfStatus() {
        var calls = new AtomicInteger();
        var error = new FakeSdkException(failure(400, Map.of("x-should-retry", "true")));
        Supplier<String> request = () -> {
            if (calls.getAndIncrement() == 0) {
                throw error;
            }
            return "ok";
        };

        String result = runWithRecordedSleep(
                request, new Options(1, 60_000L), new ArrayList<>());

        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(2);
    }

    @Test
    void parsesRetryAfterHttpDate() {
        // pi: non-numeric Retry-After ⇒ Date.parse(value) - Date.now()。
        var future = DateTimeFormatter.RFC_1123_DATE_TIME
                .withZone(ZoneOffset.UTC)
                .format(Instant.now().plusSeconds(5));
        var error = new FakeSdkException(failure(503, Map.of("retry-after", future)));
        var calls = new AtomicInteger();
        var slept = new ArrayList<Long>();
        Supplier<String> request = () -> {
            if (calls.getAndIncrement() == 0) {
                throw error;
            }
            return "ok";
        };

        String result = runWithRecordedSleep(request, new Options(1, 0L), slept);

        assertThat(result).isEqualTo("ok");
        assertThat(slept).hasSize(1);
        // 计时与解析开销允许少量误差，只断言量级（约 5s，照 pi 不夹负值）。
        assertThat(Duration.ofMillis(slept.get(0))).isBetween(
                Duration.ofSeconds(4), Duration.ofSeconds(6));
    }

    @Test
    void defaultsToZeroRetriesWhenOptionsComponentsAreNull() {
        var error = new FakeSdkException(failure(429, Map.of()));
        var calls = new AtomicInteger();
        Supplier<String> request = () -> {
            calls.incrementAndGet();
            throw error;
        };

        assertThatThrownBy(() -> ProviderRetry.retry(
                request, ADAPT, new Options(null, null), ms -> { }))
                .isSameAs(error);
        assertThat(calls).hasValue(1);
    }

    @Test
    void usesExponentialBackoffWhenNoRetryAfterHeader() {
        var slept = new ArrayList<Long>();
        var calls = new AtomicInteger();
        Supplier<String> request = () -> {
            int n = calls.getAndIncrement();
            if (n < 2) {
                throw new FakeSdkException(failure(500, Map.of()));
            }
            return "ok";
        };

        String result = runWithRecordedSleep(request, new Options(2, 0L), slept);

        assertThat(result).isEqualTo("ok");
        assertThat(slept).hasSize(2);
        // pi: min(0.5 * 2**retryIndex, 8) * 1000 * (1 - random*0.25)
        assertThat((double) slept.get(0)).isBetween(375.0, 500.0);
        assertThat((double) slept.get(1)).isBetween(750.0, 1000.0);
    }
}
