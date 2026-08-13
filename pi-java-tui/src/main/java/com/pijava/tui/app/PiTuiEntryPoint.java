package com.pijava.tui.app;

import com.pijava.coding.agent.cli.Args;
import com.pijava.coding.agent.spi.TuiEntryPoint;

/**
 * {@link TuiEntryPoint} implementation discovered by {@code Main} via
 * ServiceLoader (Phase 3 design §11.1). Registered in
 * {@code META-INF/services/com.pijava.coding.agent.spi.TuiEntryPoint}.
 */
public final class PiTuiEntryPoint implements TuiEntryPoint {

    @Override
    public int runInteractive(Args args) {
        return PiTuiApp.runInteractive(args);
    }
}
