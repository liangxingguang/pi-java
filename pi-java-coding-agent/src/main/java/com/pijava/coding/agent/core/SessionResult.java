package com.pijava.coding.agent.core;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.stream.StreamEvent;

/**
 * Live result of one {@code AgentSession.processPrompt} call (Phase 3 §10).
 *
 * <p>Unlike a plain record, this is a live object: {@link #stream()} drains
 * incremental {@link StreamEvent} values as the run produces them (driven on
 * a virtual thread), while {@link #entries()} and {@link #status()} block
 * until the run finishes.</p>
 */
public final class SessionResult {

    private final Stream<StreamEvent> stream;
    private final CompletableFuture<List<Entry>> entries;
    private final CompletableFuture<RunStatus> status;

    SessionResult(
            Stream<StreamEvent> stream,
            CompletableFuture<List<Entry>> entries,
            CompletableFuture<RunStatus> status) {
        this.stream = stream;
        this.entries = entries;
        this.status = status;
    }

    /** Incremental events (single consumption, like any Java Stream). */
    public Stream<StreamEvent> stream() {
        return stream;
    }

    /** Full transcript entries once the run finishes (blocking). */
    public List<Entry> entries() {
        return entries.join();
    }

    /** Terminal run status (blocking). */
    public RunStatus status() {
        return status.join();
    }

    /** Completion signal for the run status (non-blocking). */
    public CompletableFuture<RunStatus> statusFuture() {
        return status;
    }
}
