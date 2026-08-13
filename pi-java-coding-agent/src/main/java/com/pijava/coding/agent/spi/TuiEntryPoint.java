package com.pijava.coding.agent.spi;

import com.pijava.coding.agent.cli.Args;

/**
 * TUI interactive-mode entry point SPI.
 *
 * <p>{@code Main.main()} discovers the implementation via
 * {@link java.util.ServiceLoader} (registered by pi-java-tui in
 * {@code META-INF/services/com.pijava.coding.agent.spi.TuiEntryPoint});
 * this keeps coding-agent free of a compile-time dependency on tui.</p>
 *
 * <p>If no implementation is on the classpath, interactive mode fails with a
 * clear error instead of silently degrading (Phase 3 design §11.1).</p>
 */
public interface TuiEntryPoint {

    /** Run the interactive mode and return the process exit code. */
    int runInteractive(Args args);
}
