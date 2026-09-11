package com.pijava.agent.harness;

/**
 * A lane's record log is internally inconsistent, so orchestration state
 * cannot be derived from it.
 *
 * <p>Aligned with pi's {@code RecordLogCorruptionReason} ({@code reducer.ts}),
 * restricted to the subset of rules that need no entry lookups (docs/21 §3.4).
 * Recovery is refused rather than silently producing a wrong state.</p>
 */
final class RecordLogCorruption extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    private RecordLogCorruption(String code, String message) {
        super(code + ": " + message);
        this.code = code;
    }

    /** Build a corruption error carrying the pi-aligned reason code. */
    static RecordLogCorruption of(String code, String message) {
        return new RecordLogCorruption(code, message);
    }

    /** The pi-aligned reason code (e.g. {@code multiple_open_operations}). */
    String code() {
        return code;
    }
}
