package com.pijava.agent.session;

/**
 * Operation intent discriminator used as a {@link RecordQuery} filter
 * (aligned with pi {@code operation_started.intent.kind}).
 */
public enum OperationKind {
    RUN("run"),
    COMPACTION("compaction"),
    NAVIGATION("navigation");

    private final String value;

    OperationKind(String value) {
        this.value = value;
    }

    /** The {@code intent.kind} literal (aligned with pi). */
    public String value() {
        return value;
    }
}