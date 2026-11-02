package com.pijava.telemetry;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Serializable attribute declaration, discriminated by its value shape. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public sealed interface TelemetryAttributeDefinition {

    /** Return the value shape under pi's {@code type} JSON key. */
    @JsonProperty("type")
    TelemetryAttributeType type();

    /** Human-readable attribute description. */
    String description();

    /** Whether values may contain sensitive data. */
    Boolean sensitive();

    /** Optional cardinality hint. */
    TelemetryCardinality cardinality();

    /** Required flag for start/event/metric attributes; null for end attributes. */
    Boolean required();

    private static <T> List<T> copy(List<T> values) {
        return values == null ? null : List.copyOf(values);
    }

    private static <T> List<List<T>> copyNested(List<List<T>> values) {
        return values == null ? null : values.stream().map(List::copyOf).toList();
    }

    /** String values and examples. */
    record StringAttribute(
        String description,
        Boolean sensitive,
        TelemetryCardinality cardinality,
        Boolean required,
        List<String> values,
        List<String> examples
    ) implements TelemetryAttributeDefinition {
        /** Defensively copy declaration collections. */
        public StringAttribute {
            values = copy(values);
            examples = copy(examples);
        }

        @Override
        public TelemetryAttributeType type() {
            return TelemetryAttributeType.STRING;
        }
    }

    /** Numeric values and examples. */
    record NumberAttribute(
        String description,
        Boolean sensitive,
        TelemetryCardinality cardinality,
        Boolean required,
        List<Double> values,
        List<Double> examples
    ) implements TelemetryAttributeDefinition {
        /** Defensively copy declaration collections. */
        public NumberAttribute {
            values = copy(values);
            examples = copy(examples);
        }

        @Override
        public TelemetryAttributeType type() {
            return TelemetryAttributeType.NUMBER;
        }
    }

    /** Boolean values and examples. */
    record BooleanAttribute(
        String description,
        Boolean sensitive,
        TelemetryCardinality cardinality,
        Boolean required,
        List<Boolean> values,
        List<Boolean> examples
    ) implements TelemetryAttributeDefinition {
        /** Defensively copy declaration collections. */
        public BooleanAttribute {
            values = copy(values);
            examples = copy(examples);
        }

        @Override
        public TelemetryAttributeType type() {
            return TelemetryAttributeType.BOOLEAN;
        }
    }

    /** String-array element values and whole-array examples. */
    record StringArrayAttribute(
        String description,
        Boolean sensitive,
        TelemetryCardinality cardinality,
        Boolean required,
        List<String> elementValues,
        List<List<String>> examples
    ) implements TelemetryAttributeDefinition {
        /** Defensively copy declaration collections. */
        public StringArrayAttribute {
            elementValues = copy(elementValues);
            examples = copyNested(examples);
        }

        @Override
        public TelemetryAttributeType type() {
            return TelemetryAttributeType.STRING_ARRAY;
        }
    }

    /** Number-array element values and whole-array examples. */
    record NumberArrayAttribute(
        String description,
        Boolean sensitive,
        TelemetryCardinality cardinality,
        Boolean required,
        List<Double> elementValues,
        List<List<Double>> examples
    ) implements TelemetryAttributeDefinition {
        /** Defensively copy declaration collections. */
        public NumberArrayAttribute {
            elementValues = copy(elementValues);
            examples = copyNested(examples);
        }

        @Override
        public TelemetryAttributeType type() {
            return TelemetryAttributeType.NUMBER_ARRAY;
        }
    }

    /** Boolean-array element values and whole-array examples. */
    record BooleanArrayAttribute(
        String description,
        Boolean sensitive,
        TelemetryCardinality cardinality,
        Boolean required,
        List<Boolean> elementValues,
        List<List<Boolean>> examples
    ) implements TelemetryAttributeDefinition {
        /** Defensively copy declaration collections. */
        public BooleanArrayAttribute {
            elementValues = copy(elementValues);
            examples = copyNested(examples);
        }

        @Override
        public TelemetryAttributeType type() {
            return TelemetryAttributeType.BOOLEAN_ARRAY;
        }
    }
}
