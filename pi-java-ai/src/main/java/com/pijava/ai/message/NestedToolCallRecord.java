package com.pijava.ai.message;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * A tool call that another tool made while it ran, for example from a codemode
 * script (pi {@code NestedToolCallRecord}, {@code packages/ai/src/types.ts:598-611}).
 *
 * <p>{@code arguments} is omitted when over the size limits;
 * {@code argumentsBytes} then gives their UTF-8 JSON size.</p>
 *
 * @param id             call id, {@code <calling tool's id>/<n>}
 * @param name           tool name
 * @param arguments      call arguments (omitted over the size limits)
 * @param argumentsBytes UTF-8 size of the arguments as JSON, set when omitted
 * @param status         ok / error / unfinished
 * @param durationMs     wall time of the nested call
 * @param error          error text, truncated
 */
public record NestedToolCallRecord(
    String id,
    String name,
    JsonNode arguments,
    Long argumentsBytes,
    Status status,
    Long durationMs,
    String error
) {

    /** Call outcome states (pi {@code status: "ok" | "error" | "unfinished"}). */
    public enum Status {

        /** The call completed successfully. */
        OK("ok"),

        /** The call failed. */
        ERROR("error"),

        /** The call was still running when the calling tool finished. */
        UNFINISHED("unfinished");

        private final String wire;

        Status(String wire) {
            this.wire = wire;
        }

        /** The literal pi writes. */
        @JsonValue
        public String wire() {
            return wire;
        }

        /** Parse a wire literal. */
        public static Status fromWire(String wire) {
            for (var status : values()) {
                if (status.wire.equals(wire)) {
                    return status;
                }
            }
            throw new IllegalArgumentException("Unknown nested call status: " + wire);
        }
    }
}
