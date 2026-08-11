package com.pijava.ai.protocol;

import java.util.concurrent.LinkedBlockingQueue;

import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.stream.StreamEvent.StreamDone;
import com.pijava.ai.stream.StreamEvent.StreamError;

/**
 * Shared {@link StreamIterator} backed by a {@link LinkedBlockingQueue}.
 * Used by all four protocol adapters to bridge virtual-thread streaming
 * to synchronous iteration.
 */
final class AiQueueStreamIterator implements StreamIterator {

    private final LinkedBlockingQueue<StreamEvent> queue;
    private boolean closed;

    AiQueueStreamIterator(LinkedBlockingQueue<StreamEvent> queue) {
        this.queue = queue;
    }

    @Override
    public boolean hasNext() {
        if (closed) return false;
        try {
            var event = queue.take();
            if (event instanceof StreamDone || event instanceof StreamError) {
                closed = true;
            }
            queue.put(event);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public StreamEvent next() {
        try {
            var event = queue.take();
            if (event instanceof StreamDone || event instanceof StreamError) {
                closed = true;
            }
            return event;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new StreamError(e);
        }
    }

    @Override
    public void close() {
        closed = true;
    }
}
