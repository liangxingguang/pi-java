package com.pijava.telemetry;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Serializable metric declaration with kind-specific boundaries. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public sealed interface TelemetryMetricDefinition {

    /** Return the instrument kind under pi's {@code kind} JSON key. */
    @JsonProperty("kind")
    MetricKind kind();

    /** Human-readable instrument description. */
    String description();

    /** Optional measurement unit. */
    String unit();

    /** Attribute declarations for measurements. */
    Map<String, TelemetryAttributeDefinition> attributes();

    /** Monotonic counter declaration. */
    record Counter(
        String description,
        String unit,
        Map<String, TelemetryAttributeDefinition> attributes
    ) implements TelemetryMetricDefinition {
        /** Defensively copy declaration collections. */
        public Counter {
            attributes = Map.copyOf(attributes);
        }

        @Override
        public MetricKind kind() {
            return MetricKind.COUNTER;
        }
    }

    /** Additive up-down counter declaration. */
    record UpDownCounter(
        String description,
        String unit,
        Map<String, TelemetryAttributeDefinition> attributes
    ) implements TelemetryMetricDefinition {
        /** Defensively copy declaration collections. */
        public UpDownCounter {
            attributes = Map.copyOf(attributes);
        }

        @Override
        public MetricKind kind() {
            return MetricKind.UP_DOWN_COUNTER;
        }
    }

    /** Histogram declaration with optional bucket boundary hints. */
    record Histogram(
        String description,
        String unit,
        Map<String, TelemetryAttributeDefinition> attributes,
        List<Double> boundaries
    ) implements TelemetryMetricDefinition {
        /** Defensively copy declaration collections. */
        public Histogram {
            attributes = Map.copyOf(attributes);
            boundaries = boundaries == null ? null : List.copyOf(boundaries);
        }

        @Override
        public MetricKind kind() {
            return MetricKind.HISTOGRAM;
        }
    }
}
