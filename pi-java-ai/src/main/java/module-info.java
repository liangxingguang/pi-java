/**
 * LLM API abstraction layer for pi-java.
 *
 * <p>Defines the core interfaces for model providers, message types,
 * streaming events, and authentication. Phase 0 contains only the API
 * contracts; implementations land in Phase 1.</p>
 */
module com.pijava.ai {
    requires transitive com.pijava.telemetry;
    requires com.fasterxml.jackson.databind;
    requires java.net.http;
    requires static org.jspecify;

    exports com.pijava.ai.api;
    exports com.pijava.ai.model;
    exports com.pijava.ai.message;
    exports com.pijava.ai.stream;
    exports com.pijava.ai.provider;
    exports com.pijava.ai.auth;
    exports com.pijava.ai.catalog;
    exports com.pijava.ai.http;
}
