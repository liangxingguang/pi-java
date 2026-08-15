package com.pijava.agent.record;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Operation finish outcome (aligned with pi {@code operation_finished.outcome}). */
public enum OperationOutcome {
    COMPLETED("completed"),
    ABORTED("aborted"),
    FAILED("failed"),
    DECLINED("declined");

    private final String value;

    OperationOutcome(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static OperationOutcome fromValue(String value) {
        for (var outcome : values()) {
            if (outcome.value.equals(value)) {
                return outcome;
            }
        }
        throw new IllegalArgumentException("Unknown operation outcome: " + value);
    }
}