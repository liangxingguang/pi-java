package com.pijava.coding.agent.codemode;

import java.util.List;

/**
 * Result details of one codemode call, mirroring pi's {@code CodemodeToolDetails}.
 *
 * @param calls nested tool-call records
 * @param fullOutputPath temp file holding the untruncated output, when applicable
 */
public record CodemodeDetails(List<NestedCall> calls, String fullOutputPath) {
    /** A nested tool call recorded for TUI display. */
    public record NestedCall(
            String id,
            String name,
            String args,
            String status,
            Double durationMs,
            String error,
            Double cost) {

        /** Creates a call without cost or duration. */
        public NestedCall(String id, String name, String args, String status) {
            this(id, name, args, status, null, null, null);
        }
    }
}
