package com.pijava.agent.record;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Tool replay discriminator (aligned with pi {@code tool_started.replay}). */
public enum ReplayKind {
    NEVER("never"),
    SAFE("safe");

    private final String value;

    ReplayKind(String value) {
        this.value = value;
    }

    /** The serialized replay kind value. */
    @JsonValue
    public String value() {
        return value;
    }

    /** Parse a replay kind from its serialized value. */
    @JsonCreator
    public static ReplayKind fromValue(String value) {
        for (var kind : values()) {
            if (kind.value.equals(value)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unknown replay kind: " + value);
    }
}
