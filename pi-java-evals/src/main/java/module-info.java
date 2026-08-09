/**
 * Evaluation framework for pi-java.
 *
 * <p>Contains conformance tests, smoke tests, and extension tests
 * that validate provider implementations and agent behaviour.
 * Full implementation in Phase 6.</p>
 */
module com.pijava.evals {
    requires com.pijava.agent;
    requires com.pijava.coding.agent;

    // exports com.pijava.evals; — Phase 6
}
