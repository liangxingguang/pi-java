/**
 * Telemetry contracts for pi-java.
 *
 * <p>Provides the {@link com.pijava.telemetry.TelemetryContext} and
 * {@link com.pijava.telemetry.TelemetrySpan} interfaces for span-based
 * tracing. Phase 0 ships with a no-op implementation; a full OpenTelemetry
 * adapter is planned for a future phase.</p>
 */
module com.pijava.telemetry {
    requires static org.jspecify;

    exports com.pijava.telemetry;
}
