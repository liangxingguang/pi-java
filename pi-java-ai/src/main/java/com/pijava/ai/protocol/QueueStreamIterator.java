package com.pijava.ai.protocol;

import java.util.concurrent.LinkedBlockingQueue;

import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.stream.StreamEvent.StreamDone;
import com.pijava.ai.stream.StreamEvent.StreamError;

/**
 * Shared {@link StreamIterator} backed by a {@link LinkedBlockingQueue}.
 *
 * <p>Used by protocol adapters that bridge {@code Flow.Publisher} to
 * blocking iteration. Thread-safe: the publisher pushes events to the
 * queue on a virtual thread while the consumer pulls on the calling thread.</p>
 */
final class QueueStreamIterator implements StreamIterator {

    private final LinkedBlockingQueue<StreamEvent> queue;
    private boolean closed;
    /** Element peeked by {@link #hasNext()} and handed to {@link #next()}. */
    private StreamEvent pending;

    QueueStreamIterator(LinkedBlockingQueue<StreamEvent> queue) {
        this.queue = queue;
    }

    @Override
    public boolean hasNext() {
        if (closed) return false;
        try {
            if (pending == null) {
                pending = queue.take();
            }
            if (pending instanceof StreamDone || pending instanceof StreamError) {
                closed = true;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public StreamEvent next() {
        try {
            var event = pending != null ? pending : queue.take();
            pending = null;
            if (event instanceof StreamDone || event instanceof StreamError) {
                closed = true;
            }
            return event;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new StreamError("error", e, AssistantMessage.empty());
        }
    }

    @Override
    public void close() {
        closed = true;
    }
}
