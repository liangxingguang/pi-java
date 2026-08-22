package com.pijava.web;

import com.pijava.coding.agent.cli.Args;
import com.pijava.coding.agent.spi.WebEntryPoint;

/**
 * {@link WebEntryPoint} implementation discovered by {@code Main} via
 * ServiceLoader (Phase 7 design §2). Registered in
 * {@code META-INF/services/com.pijava.coding.agent.spi.WebEntryPoint}.
 */
public final class WebEntryPointImpl implements WebEntryPoint {

    @Override
    public int runWeb(Args args) {
        return PiWebServer.run(args);
    }
}
