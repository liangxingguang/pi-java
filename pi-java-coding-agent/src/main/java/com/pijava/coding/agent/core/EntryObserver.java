package com.pijava.coding.agent.core;

import com.pijava.agent.entry.Entry;

/**
 * Receives complete transcript entries as a run progresses (Phase 3 §11.1).
 *
 * <p>Defined in coding-agent (which observes agent-core {@link Entry});
 * the TUI module implements it for bubble rendering. The coding-agent module
 * never references TUI types.</p>
 */
@FunctionalInterface
public interface EntryObserver {

    /** Called for each complete {@link Entry} produced by a run. */
    void onEntry(Entry entry);
}
