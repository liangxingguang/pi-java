package com.pijava.mcp.protocol;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Tool execution metadata ({@code types.ts:72-74}).
 *
 * @param taskSupport whether the tool supports tasks
 */
public record ToolExecution(@Nullable TaskSupport taskSupport) {

    /** Task support level. */
    public enum TaskSupport {

        FORBIDDEN("forbidden"),
        OPTIONAL("optional"),
        REQUIRED("required");

        private final String wire;

        TaskSupport(String wire) {
            this.wire = wire;
        }

        /** Wire value. */
        @JsonValue
        public String wire() {
            return wire;
        }

        /** Parse a wire value; unknown values yield {@code null}. */
        @JsonCreator
        public static @Nullable TaskSupport fromWire(String value) {
            for (var level : values()) {
                if (level.wire.equals(value)) {
                    return level;
                }
            }
            return null;
        }
    }
}
