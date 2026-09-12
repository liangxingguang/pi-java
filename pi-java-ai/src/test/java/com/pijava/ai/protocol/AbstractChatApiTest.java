package com.pijava.ai.protocol;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for {@link AbstractChatApi#stream} subscriber-attachment
 * ordering.
 *
 * <p>These tests are deliberately built on a zero-I/O producer so that the
 * producer's race against {@code subscribe()} becomes <em>deterministic</em>
 * rather than flaky. The bug being pinned is: {@code SubmissionPublisher.submit()}
 * silently discards an item when there is no subscriber yet, so a producer that
 * wins the race to its first {@code submit()} loses that event forever. A
 * network-backed adapter only hides this because its second {@code submit()}
 * happens after a round-trip, by which point the subscriber has attached.</p>
 */
class AbstractChatApiTest {

    private static final StreamEvent EVENT =
        new StreamEvent.TextStart(0, AssistantMessage.empty());

    private static StreamRequest request() {
        return StreamRequest.of(ModelId.of("test", "zero-io"), List.of());
    }

    /**
     * A provider adapter whose {@code streamInternal} emits one known event
     * immediately and returns, performing no I/O at all.
     */
    private static final class ZeroIoApi extends AbstractChatApi {

        private final StreamEvent event;
        private final AtomicInteger invocations = new AtomicInteger();

        ZeroIoApi(StreamEvent event) {
            this.event = event;
        }

        @Override
        protected void streamInternal(StreamRequest request,
                                      SubmissionPublisher<StreamEvent> publisher) {
            invocations.incrementAndGet();
            publisher.submit(event);
        }
    }

    /** Collects received events and releases the latch once the stream completes. */
    private static Flow.Subscriber<StreamEvent> collector(
            List<StreamEvent> sink, CountDownLatch done) {
        return new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) {
                s.request(Long.MAX_VALUE);
            }
            @Override public void onNext(StreamEvent e) { sink.add(e); }
            @Override public void onError(Throwable t) { done.countDown(); }
            @Override public void onComplete() { done.countDown(); }
        };
    }

    /**
     * The event submitted by a producer that finishes <em>before</em> anyone
     * subscribes must still be delivered.
     *
     * <p><b>Why the sleep makes this deterministic, not flaky:</b> the producer
     * does no I/O and submits exactly one item, so its entire lifetime is a few
     * microseconds of pure in-memory work. Sleeping 200 ms — five orders of
     * magnitude longer — guarantees the producer has already submitted (and
     * closed) <em>before</em> {@code subscribe()} is called, so the sequence of
     * observable events is fixed: with the unfixed code the item is submitted
     * with zero subscribers, dropped, and the later subscriber sees only
     * {@code onComplete}; with the fix the subscriber attaches first, so the
     * producer cannot start until it has. There is no timing window left for the
     * outcome to vary — the sleep is not "waiting for a race", it is collapsing
     * the race entirely by making the producer finish first, always.</p>
     */
    @Test
    void eventSubmittedBeforeSubscribeIsNotDropped() throws Exception {
        var api = new ZeroIoApi(EVENT);
        var publisher = api.stream(request(), ApiOptions.defaults());

        // Let the producer finish entirely: it does no I/O, so this is ample.
        Thread.sleep(200);

        var received = new CopyOnWriteArrayList<StreamEvent>();
        var done = new CountDownLatch(1);
        publisher.subscribe(collector(received, done));

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(received).containsExactly(EVENT);
    }

    /** A second subscriber must not start a second producer run. */
    @Test
    void secondSubscriberDoesNotRestartTheProducer() throws Exception {
        var api = new ZeroIoApi(EVENT);
        var publisher = api.stream(request(), ApiOptions.defaults());

        var first = new CopyOnWriteArrayList<StreamEvent>();
        var firstDone = new CountDownLatch(1);
        publisher.subscribe(collector(first, firstDone));
        assertThat(firstDone.await(5, TimeUnit.SECONDS)).isTrue();

        // The shared collector counts its latch down in onError too, so it can
        // only prove the late subscriber *terminated*. This subscriber keeps a
        // latch that is released by onComplete alone, plus the error it saw, so
        // the assertion below pins *normal* completion, not mere termination.
        var second = new CopyOnWriteArrayList<StreamEvent>();
        var secondCompleted = new CountDownLatch(1);
        var secondError = new AtomicReference<Throwable>();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) {
                s.request(Long.MAX_VALUE);
            }
            @Override public void onNext(StreamEvent e) { second.add(e); }
            @Override public void onError(Throwable t) { secondError.set(t); }
            @Override public void onComplete() { secondCompleted.countDown(); }
        });

        assertThat(secondCompleted.await(5, TimeUnit.SECONDS))
            .as("late subscriber must complete normally; error was: %s", secondError)
            .isTrue();

        assertThat(api.invocations).hasValue(1);
        assertThat(first).containsExactly(EVENT);
        // The publisher is already closed, so the late subscriber is completed
        // but receives nothing: events are never replayed to late subscribers.
        assertThat(second).isEmpty();
    }
}
