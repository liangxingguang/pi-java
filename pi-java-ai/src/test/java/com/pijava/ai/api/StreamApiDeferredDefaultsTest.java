package com.pijava.ai.api;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicBoolean;

import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.DeferredHandle;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * docs/20 §6.4：{@link StreamApi} 的 deferred 三入口默认方法。
 */
class StreamApiDeferredDefaultsTest {

    private static final DeferredHandle HANDLE = new DeferredHandle(
        "faux", "faux-1", "faux", "deferred:1", null, null, null);
    private static final ModelInfo MODEL = ModelInfo.minimal(ModelId.of("faux", "faux-1"));

    @Test
    void unsupportedImplementationRaisesInsteadOfSilentlyDoingNothing() {
        var api = new StreamApi() {
            @Override
            public Flow.Publisher<StreamEvent> stream(StreamRequest request, ApiOptions options) {
                throw new UnsupportedOperationException();
            }

            @Override
            public StreamIterator streamBlocking(StreamRequest request, ApiOptions options) {
                throw new UnsupportedOperationException();
            }
        };

        assertThatThrownBy(() -> api.fetchDeferred(MODEL, HANDLE, ApiOptions.defaults()))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> api.cancelDeferred(MODEL, HANDLE, ApiOptions.defaults()))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void blockingFetchCollectsEventsUntilTheTerminal() {
        var api = new StreamApi() {
            @Override
            public Flow.Publisher<StreamEvent> stream(StreamRequest request, ApiOptions options) {
                throw new UnsupportedOperationException();
            }

            @Override
            public StreamIterator streamBlocking(StreamRequest request, ApiOptions options) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Flow.Publisher<StreamEvent> fetchDeferred(
                    ModelInfo model, DeferredHandle handle, ApiOptions options) {
                return subscriber -> {
                    var publisher = new SubmissionPublisher<StreamEvent>();
                    publisher.subscribe(subscriber);
                    Thread.startVirtualThread(() -> {
                        publisher.submit(new StreamEvent.Start(AssistantMessage.empty()));
                        publisher.submit(StreamEvent.StreamDone.settle(
                            StopReason.STOP, AssistantMessage.empty().withStopReason(StopReason.STOP)));
                        publisher.close();
                    });
                };
            }
        };

        var events = new ArrayList<StreamEvent>();
        try (var iter = api.fetchDeferredBlocking(MODEL, HANDLE, ApiOptions.defaults())) {
            while (iter.hasNext()) {
                events.add(iter.next());
            }
        }

        assertThat(events).hasSize(2);
        assertThat(events.get(1)).isInstanceOf(StreamEvent.StreamDone.class);
    }

    @Test
    void blockingFetchSynthesizesAnErrorWhenTheProducerNeverTerminates() {
        var started = new AtomicBoolean();
        var api = new StreamApi() {
            @Override
            public Flow.Publisher<StreamEvent> stream(StreamRequest request, ApiOptions options) {
                throw new UnsupportedOperationException();
            }

            @Override
            public StreamIterator streamBlocking(StreamRequest request, ApiOptions options) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Flow.Publisher<StreamEvent> fetchDeferred(
                    ModelInfo model, DeferredHandle handle, ApiOptions options) {
                return subscriber -> {
                    started.set(true);
                    var publisher = new SubmissionPublisher<StreamEvent>();
                    publisher.subscribe(subscriber);
                    publisher.close();
                };
            }
        };

        var events = new ArrayList<StreamEvent>();
        try (var iter = api.fetchDeferredBlocking(MODEL, HANDLE, ApiOptions.defaults())) {
            while (iter.hasNext()) {
                events.add(iter.next());
            }
        }

        assertThat(started).isTrue();
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(StreamEvent.StreamError.class);
    }

    @Test
    void withExtraReplacesTheExtraMapAndPreservesEveryOtherComponent() {
        var original = new ApiOptions("/base", "key", Duration.ofSeconds(7), 3,
            Map.of("sessionId", "s1"), AuthKind.BEARER, null, null, Map.of("REGION", "eu"));

        var replaced = ApiOptions.withExtra(original, Map.of("deferred", Boolean.TRUE));

        assertThat(replaced.baseUrl()).isEqualTo("/base");
        assertThat(replaced.apiKey()).isEqualTo("key");
        assertThat(replaced.timeout()).isEqualTo(Duration.ofSeconds(7));
        assertThat(replaced.maxRetries()).isEqualTo(3);
        assertThat(replaced.authKind()).isEqualTo(AuthKind.BEARER);
        assertThat(replaced.env()).isEqualTo(Map.of("REGION", "eu"));
        assertThat(replaced.extra()).containsExactlyEntriesOf(Map.of("deferred", Boolean.TRUE));
    }

    @Test
    void blockingFetchIsUsableWithoutAnyTerminalGuardTricks() {
        List<StreamEvent> step = List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            StreamEvent.StreamDone.settle(StopReason.STOP, AssistantMessage.empty().withStopReason(StopReason.STOP)));
        var api = new StreamApi() {
            @Override
            public Flow.Publisher<StreamEvent> stream(StreamRequest request, ApiOptions options) {
                throw new UnsupportedOperationException();
            }

            @Override
            public StreamIterator streamBlocking(StreamRequest request, ApiOptions options) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Flow.Publisher<StreamEvent> fetchDeferred(
                    ModelInfo model, DeferredHandle handle, ApiOptions options) {
                return subscriber -> {
                    var publisher = new SubmissionPublisher<StreamEvent>();
                    publisher.subscribe(subscriber);
                    step.forEach(publisher::submit);
                    publisher.close();
                };
            }
        };

        var events = new ArrayList<StreamEvent>();
        try (var iter = api.fetchDeferredBlocking(MODEL, HANDLE, ApiOptions.defaults())) {
            while (iter.hasNext()) {
                events.add(iter.next());
            }
        }

        assertThat(events).isEqualTo(step);
    }
}
