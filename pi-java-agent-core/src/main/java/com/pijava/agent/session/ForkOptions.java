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
        public sealed interface Position permits At, Before {}

        /** Fork at (including) the entry. */
        public record At() implements Position {}

        /** Fork before the entry. */
        public record Before() implements Position {}
    }

    /** Tree scope: copy all entries, lanes and branch tips. */
    record Tree() implements ForkOptions {}
}
