package com.pijava.codemode.runtime;

import java.util.Map;

import com.pijava.codemode.api.CodeModeAbortSignal;

/** Per-execution options; defaults mirror the TypeScript implementation. */
public record CodeModeExecuteOptions(long timeoutMs, Map<String, Object> store, CodeModeAbortSignal signal) {
    /** Creates options with the default timeout, no store, and no cancellation. */
    public CodeModeExecuteOptions() {
        this(CodeModeExecution.DEFAULT_TIMEOUT_MS, Map.of(), null);
    }

    /** Creates options with the default timeout and the given store. */
    public CodeModeExecuteOptions(Map<String, Object> store) {
        this(CodeModeExecution.DEFAULT_TIMEOUT_MS, store, null);
    }

    /** Creates options with an explicit timeout and store. */
    public CodeModeExecuteOptions(long timeoutMs, Map<String, Object> store) {
        this(timeoutMs, store, null);
    }
}
