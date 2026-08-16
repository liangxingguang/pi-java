package com.pijava.agent.session;

/**
 * Fork scope options (aligned with pi {@code ForkOptions}).
 */
public sealed interface ForkOptions {

    /**
     * Branch scope: copy only the selected path. A {@code null} {@code entryId}
     * forks at the main leaf; a non-null {@code entryId} with a {@code null}
     * position forks "before" it.
     */
    record Branch(String entryId, Position position) implements ForkOptions {

        /** Position relative to the selected entry. */
        public enum Position {
            /** Fork at (including) the entry. */
            AT,
            /** Fork before the entry. */
            BEFORE
        }
    }

    /** Tree scope: copy all entries, lanes and branch tips. */
    record Tree() implements ForkOptions {}
}
