package com.pijava.agent.entry;

/**
 * An entry that has been provisioned (created with header and ID) but not yet
 * written to persistent storage.
 *
 * <p>Used in {@code LaneState.pendingWrites} to bridge the gap between
 * entry creation (inside the harness) and entry persistence (by the outer
 * driver via {@code AppendEntry} action).</p>
 */
public final class ProvisionedEntry {

    private final Entry entry;
    private volatile boolean written;

    public ProvisionedEntry(Entry entry) {
        this.entry = entry;
        this.written = false;
    }

    public Entry entry() { return entry; }
    public boolean isWritten() { return written; }

    /** Mark this entry as persisted. Idempotent. */
    public void markWritten() {
        this.written = true;
    }
}
