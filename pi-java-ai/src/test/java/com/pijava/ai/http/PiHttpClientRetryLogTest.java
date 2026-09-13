package com.pijava.ai.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicInteger;

import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.protocol.AbstractChatApi;
import com.pijava.ai.stream.StreamEvent;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.sun.net.httpserver.HttpServer;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Tests for observability logging in {@link PiHttpClient} and
 * {@link AbstractChatApi} (18-observability-design §7, commit 8):
 * HTTP retry WARN and stream-error WARN.
 */
class PiHttpClientRetryLogTest {

    @Test
    void retryLogsWarnWithStatusAttemptAndRetryAfter() throws Exception {
        var logger = attachAppender(PiHttpClient.class);

        var hits = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            if (hits.incrementAndGet() == 1) {
                exchange.getResponseHeaders().add("Retry-After", "0");
                exchange.sendResponseHeaders(429, -1);
            } else {
                byte[] body = "data: {\"ok\":true}\n\n".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        try {
            var client = PiHttpClient.builder()
                .retryPolicy(new RetryPolicy.Builder()
                    .maxRetries(1).baseDelay(Duration.ofMillis(5)).build())
                .build();
            var it = client.postSse(
                "http://localhost:" + server.getAddress().getPort() + "/", "{}", Map.of());

            assertThat(it.hasNext()).isTrue();
            assertThat(it.next().data()).isEqualTo("{\"ok\":true}");
            assertThat(hits.get()).isEqualTo(2);

            var warns = logger.warns();
            assertThat(warns).hasSize(1);
            assertThat(warns.get(0))
                .contains("HTTP 429").contains("retry 1/1").contains("Retry-After: 0");
        } finally {
            server.stop(0);
            logger.detach();
        }
    }

    @Test
    void streamErrorLogsWarn() throws Exception {
        var logger = attachAppender(AbstractChatApi.class);

        var api = new AbstractChatApi() {
            @Override
            public String apiName() {
                return "openai-responses";
            }

            @Override
            protected void streamInternal(StreamRequest request,
                                          SubmissionPublisher<StreamEvent> publisher) {
                throw new IllegalStateException("boom");
            }
        };
        try {
            var request = StreamRequest.of(ModelId.of("test", "model"), List.of());
            try (var iter = api.streamBlocking(request, null)) {
                boolean sawError = false;
                while (iter.hasNext()) {
                    if (iter.next() instanceof StreamEvent.StreamError) {
                        sawError = true;
                        break;
                    }
                }
                assertThat(sawError).isTrue();
            }
            var warns = logger.warns();
            assertThat(warns).hasSize(1);
            assertThat(warns.get(0))
                .contains("LLM stream failed").contains("test/model");
        } finally {
            logger.detach();
        }
    }

    // ── log capture helper ────────────────────────────────────

    private static CapturedLogger attachAppender(Class<?> type) {
        var logger = (Logger) LoggerFactory.getLogger(type);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        Level previous = logger.getLevel();
        logger.setLevel(Level.WARN);
        return new CapturedLogger(logger, appender, previous);
    }

    private record CapturedLogger(Logger logger, ListAppender<ILoggingEvent> appender,
                                  Level previous) {
        List<String> warns() {
            return appender.list.stream()
                .filter(ev -> ev.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
        }

        void detach() {
            logger.detachAppender(appender);
            logger.setLevel(previous);
        }
    }
}
