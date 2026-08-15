package com.pijava.agent.record;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Usage cause discriminator (aligned with pi {@code usage.cause}). */
public enum UsageCause {
    ASSISTANT("assistant"),
    COMPACTION("compaction"),
    BRANCH_SUMMARY("branch_summary"),
    DEFERRED_FETCH("deferred_fetch"),
    TOOL("tool"),
    HOOK("hook"),
    ADJUSTMENT("adjustment");

    private final String value;

    UsageCause(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static UsageCause fromValue(String value) {
        for (var cause : values()) {
            if (cause.value.equals(value)) {
                return cause;
            }
        }
        throw new IllegalArgumentException("Unknown usage cause: " + value);
    }
}