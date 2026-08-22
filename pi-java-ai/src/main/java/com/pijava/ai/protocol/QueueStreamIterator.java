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
        // Unblock a consumer parked in hasNext()/next() take(): a closed stream
        // that never received a terminal event would otherwise hang forever.
        queue.offer(new StreamDone("aborted", null, AssistantMessage.empty()));
    }

    /**
     * Abort the stream early: drop any buffered events and unblock a waiting
     * consumer with an error (pi {@code EventStream.abort}).
     *
     * <p>Backpressure needs no explicit support — the pull-based {@code take()}
     * already throttles the producer. Abort is the one true gap: without it, a
     * producer that stops without a terminal event leaves the consumer blocked.
     * {@code closed} is deliberately <em>not</em> set here so the injected
     * {@link StreamError} is delivered to the consumer (which sets {@code closed}
     * when it reads it); the FIFO queue guarantees the error precedes any event
     * a racing producer appends.</p>
     */
    public void abort(Throwable cause) {
        if (closed) {
            return;
        }
        queue.clear();
        queue.offer(new StreamError("aborted", cause, AssistantMessage.empty()));
    }
}
