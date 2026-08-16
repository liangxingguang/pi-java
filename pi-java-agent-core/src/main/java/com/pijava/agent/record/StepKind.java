package com.pijava.agent.record;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Step discriminator (aligned with pi {@code step_attempt.step}). */
public enum StepKind {
    ASSISTANT("assistant"),
    COMPACTION("compaction"),
    BRANCH_SUMMARY("branch_summary");

    private final String value;

    StepKind(String value) {
        this.value = value;
    }

    /** The serialized step kind value. */
    @JsonValue
    public String value() {
        return value;
    }

    /** Parse a step kind from its serialized value. */
    @JsonCreator
    public static StepKind fromValue(String value) {
        for (var kind : values()) {
            if (kind.value.equals(value)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unknown step kind: " + value);
    }
}
