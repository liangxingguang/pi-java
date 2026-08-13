package com.pijava.coding.agent.core;

import com.pijava.ai.stream.StreamEvent;

/**
 * Receives incremental {@link StreamEvent} values as a run streams
 * (Phase 3 §11.1) — the basis for typewriter-style rendering in the TUI.
 */
@FunctionalInterface
public interface StreamObserver {

    /** Called for each stream event produced by the current run. */
    void onStreamEvent(StreamEvent event);
}
