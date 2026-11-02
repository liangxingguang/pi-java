package com.pijava.telemetry;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;

/** Declarative parent vocabulary; no runtime parent validation is implied. */
public sealed interface TelemetryParentDefinition {

    /** Return the parent definition kind for schema serialization. */
    @JsonProperty("kind")
    Kind kind();

    /** Closed set of parent definition kinds. */
    enum Kind {
        ANY("any"),
        ROOT_OR_EXTERNAL("root_or_external"),
        SPANS("spans");

        private final String wireName;

        Kind(String wireName) {
            this.wireName = wireName;
        }

        /** Return the schema wire name. */
        @JsonValue
        public String wireName() {
            return wireName;
        }
    }

    /** Any parent is accepted by the declaration. */
    record Any() implements TelemetryParentDefinition {
        @Override
        public Kind kind() {
            return Kind.ANY;
        }
    }

    /** A root or externally owned parent is accepted by the declaration. */
    record RootOrExternal() implements TelemetryParentDefinition {
        @Override
        public Kind kind() {
            return Kind.ROOT_OR_EXTERNAL;
        }
    }

    /** A declaration naming accepted parent span names. */
    record Spans(List<String> spans) implements TelemetryParentDefinition {
        /** Defensively copy accepted parent names. */
        public Spans {
            spans = List.copyOf(spans);
        }

        @Override
        public Kind kind() {
            return Kind.SPANS;
        }
    }
}
