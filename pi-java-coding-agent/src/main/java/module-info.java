/**
 * pi-java coding agent — CLI entry point.
 *
 * <p>The main entry point for the pi-java CLI. Combines the agent
 * harness with the TUI and exposes ~40 CLI options via Picocli.
 * Full implementation in Phase 3.</p>
 */
module com.pijava.coding.agent {
    requires com.pijava.agent;
    requires com.pijava.tui;
    requires info.picocli;

    // exports com.pijava.coding.agent; — Phase 3
}
