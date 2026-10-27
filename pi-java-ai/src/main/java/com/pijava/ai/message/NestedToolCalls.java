package com.pijava.ai.message;

import java.util.List;

/**
 * Bounded record of the nested calls a tool made. Results are not recorded
 * (pi {@code NestedToolCalls}, {@code packages/ai/src/types.ts:613-618}).
 *
 * @param calls    the recorded calls
 * @param complete false when calls were dropped, arguments omitted, or calls
 *                 had not finished
 */
public record NestedToolCalls(List<NestedToolCallRecord> calls, boolean complete) {

    /** Compact constructor: defensive copy. */
    public NestedToolCalls {
        calls = List.copyOf(calls);
    }
}
