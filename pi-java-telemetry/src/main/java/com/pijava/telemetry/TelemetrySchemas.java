package com.pijava.telemetry;

/** Identity and typed-runtime binders for serializable telemetry schemas. */
public final class TelemetrySchemas {

    private TelemetrySchemas() {
    }

    /** Return the exact schema instance, matching pi's typed identity helper. */
    public static TelemetrySchemaDefinition defineTelemetrySchema(TelemetrySchemaDefinition schema) {
        return schema;
    }

    /** Bind an explicit parent; schema values are not inspected at runtime. */
    public static TypedSpanStarter createTypedSpanStarter(
        TelemetryContext parent, TelemetrySchemaDefinition... schemas
    ) {
        return TypedTelemetrySpanStarter.bind(parent);
    }

    /** Bind metric names to merged schema metadata; later definitions win. */
    public static TypedTelemetryMetrics createTypedMetrics(
        TelemetryMetrics metrics, TelemetrySchemaDefinition... schemas
    ) {
        return new TypedTelemetryMetrics(metrics, schemas);
    }
}
