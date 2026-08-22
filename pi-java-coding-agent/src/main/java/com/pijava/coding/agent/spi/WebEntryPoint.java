package com.pijava.coding.agent.spi;

import com.pijava.coding.agent.cli.Args;

/**
 * Web UI mode entry point SPI ({@code --mode web}).
 *
 * <p>{@code Main.main()} discovers the implementation via
 * {@link java.util.ServiceLoader} (registered by pi-java-web in
 * {@code META-INF/services/com.pijava.coding.agent.spi.WebEntryPoint});
 * this keeps coding-agent free of a compile-time dependency on web.</p>
 *
 * <p>If no implementation is on the classpath, web mode fails with a clear
 * error instead of silently degrading (Phase 7 design §2).</p>
 */
public interface WebEntryPoint {

    /** Run the web UI and return the process exit code. */
    int runWeb(Args args);
}
