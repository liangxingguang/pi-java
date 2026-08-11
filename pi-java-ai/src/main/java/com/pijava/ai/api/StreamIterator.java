package com.pijava.ai.api;

import java.util.Iterator;
import java.util.List;

import com.pijava.ai.stream.StreamEvent;

/**
 * A synchronous, closeable iterator over {@link StreamEvent} values.
 *
 * <p>Designed for use with virtual threads: call {@link #next()} to
 * block until the next event arrives. Always close the iterator to
 * release underlying HTTP resources.</p>
 */
public interface StreamIterator extends Iterator<StreamEvent>, AutoCloseable {

    /**
     * Returns {@code true} if the stream has more events.
     * Blocks until an event arrives or the stream ends.
     */
    @Override
    boolean hasNext();

    /**
     * Returns the next stream event, blocking if necessary.
     *
     * @return the next event
     * @throws java.util.NoSuchElementException if the stream is exhausted
     */
    @Override
    StreamEvent next();

    /**
     * Close the underlying connection and release resources.
     * Safe to call multiple times.
     */
    @Override
    void close();

    /** Create a StreamIterator from a pre-built list of events (for testing). */
    static StreamIterator from(List<StreamEvent> events) {
        return new StreamIterator() {
            private final Iterator<StreamEvent> iter = events.iterator();
            @Override public boolean hasNext() { return iter.hasNext(); }
            @Override public StreamEvent next() { return iter.next(); }
            @Override public void close() {}
        };
    }
}
