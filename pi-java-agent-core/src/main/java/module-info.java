/**
 * Agent runtime core for pi-java.
 *
 * <p>Contains the {@link com.pijava.agent.harness.AgentHarness}, tool
 * system, session storage abstractions, and compaction logic. Phase 0
 * defines the public API; implementations land in Phase 2.</p>
 */
module com.pijava.agent {
    requires transitive com.pijava.ai;
    requires com.fasterxml.jackson.databind;
    requires static org.jspecify;

    exports com.pijava.agent.harness;
    exports com.pijava.agent.session;
    exports com.pijava.agent.compaction;
}
