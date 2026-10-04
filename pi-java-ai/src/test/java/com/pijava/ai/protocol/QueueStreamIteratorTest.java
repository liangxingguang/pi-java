package com.pijava.ai.protocol;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.stream.StreamEvent;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * QueueStreamIterator — abort/close 解除阻塞（pi {@code EventStream.abort}）。
 */
class QueueStreamIteratorTest {

    @Test
    void abortUnblocksWaitingConsumerWithError() throws Exception {
        var iterator = new QueueStreamIterator(new LinkedBlockingQueue<>());
        var result = new AtomicReference<StreamEvent>();
        var thread = new Thread(() -> result.set(iterator.hasNext() ? iterator.next() : null));
        thread.start();
        Thread.sleep(50); // let the consumer park in take()
        iterator.abort(new RuntimeException("boom"));
        thread.join(2000);

        assertThat(result.get()).isInstanceOf(StreamEvent.StreamError.class);
        assertThat(((StreamEvent.StreamError) result.get()).error()).hasMessage("boom");
        assertThat(iterator.hasNext()).isFalse();
    }

    @Test
    void closeUnblocksWaitingConsumerWithDone() throws Exception {
        var iterator = new QueueStreamIterator(new LinkedBlockingQueue<>());
        var result = new AtomicReference<StreamEvent>();
        var thread = new Thread(() -> result.set(iterator.hasNext() ? iterator.next() : null));
        thread.start();
        Thread.sleep(50);
        iterator.close();
        thread.join(2000);

        assertThat(result.get()).isInstanceOf(StreamEvent.StreamDone.class);
        assertThat(iterator.hasNext()).isFalse();
    }

    /**
     * C 批次（{@code 原 docs/55}）：这条合成错误**没有累加器**（pi 的对等物是
     * {@code lazy.ts} 的合成错误消息），所以内容仍为空 —— 但落定那一半必须有：
     * {@code reason} 与消息上的 {@code stopReason} 同值，且文本非空，否则下游
     * （打印模式、重试分类器、宿主状态）只能回头读 {@code Throwable}。
     */
    @Test
    void abortSettlesTheReasonAndText() {
        var iterator = new QueueStreamIterator(new LinkedBlockingQueue<>());
        iterator.abort(new RuntimeException("boom"));

        var event = (StreamEvent.StreamError) iterator.next();
        assertThat(event.reason()).isEqualTo("aborted");
        assertThat(event.partial().stopReason()).isEqualTo("aborted");
        assertThat(event.partial().errorMessage()).isEqualTo("boom");
    }

    @Test
    void closeSettlesTheStopReasonOnTheSyntheticDone() {
        var iterator = new QueueStreamIterator(new LinkedBlockingQueue<>());
        iterator.close();

        var done = (StreamEvent.StreamDone) iterator.next();
        assertThat(done.reason()).isEqualTo("aborted");
        assertThat(done.partial().stopReason()).isEqualTo("aborted");
    }

    @Test
    void abortDropsBufferedEvents() {
        var queue = new LinkedBlockingQueue<StreamEvent>();
        queue.offer(new StreamEvent.TextStart(0, AssistantMessage.empty()));
        var iterator = new QueueStreamIterator(queue);
        iterator.abort(new RuntimeException("boom"));

        assertThat(iterator.hasNext()).isTrue();
        assertThat(iterator.next()).isInstanceOf(StreamEvent.StreamError.class);
        assertThat(iterator.hasNext()).isFalse();
    }

    @Test
    void iteratesBufferedEventsUntilDone() {
        var queue = new LinkedBlockingQueue<StreamEvent>();
        var done = AssistantMessage.empty();
        queue.offer(new StreamEvent.TextStart(0, done));
        queue.offer(new StreamEvent.StreamDone("stop", null, done));
        var iterator = new QueueStreamIterator(queue);

        assertThat(iterator.next()).isInstanceOf(StreamEvent.TextStart.class);
        assertThat(iterator.next()).isInstanceOf(StreamEvent.StreamDone.class);
        assertThat(iterator.hasNext()).isFalse();
    }
}
