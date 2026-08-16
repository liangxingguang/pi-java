package com.pijava.agent.record;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Queue discriminator (aligned with pi {@code queue_enqueued.queue}). */
public enum QueueKind {
    STEER("steer"),
    FOLLOW_UP("followUp"),
    NEXT_RUN("nextRun");

    private final String value;

    QueueKind(String value) {
        this.value = value;
    }

    /** The serialized queue kind value. */
    @JsonValue
    public String value() {
        return value;
    }

    /** Parse a queue kind from its serialized value. */
    @JsonCreator
    public static QueueKind fromValue(String value) {
        for (var kind : values()) {
            if (kind.value.equals(value)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unknown queue kind: " + value);
    }
}
