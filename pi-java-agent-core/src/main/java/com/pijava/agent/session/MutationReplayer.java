package com.pijava.agent.session;

/**
 * Optional capability of a {@link SessionStorage}: apply a raw
 * {@link SessionMutation} preserving its original {@code seq}/{@code parentId}/
 * {@code timestamp} rather than re-deriving them.
 *
 * <p>The SQLite backend implements this so {@code /import} can replay a JSONL
 * file byte-faithfully (§4.7). JSONL copies the source file and Memory is not
 * imported, so neither needs to implement it.</p>
 */
public interface MutationReplayer {
    /** Apply the mutation with its original sequence and timestamps. */
    void replayMutation(SessionMutation mutation);
}
