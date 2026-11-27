package com.pijava.coding.agent.codemode;

import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.ExtensionContext;

/** Built-in {@code codemode} extension: registers the tool and its TUI renderer. */
public final class CodemodeExtension implements PiExtension {
    /** Extension name, verbatim from pi's {@code index.ts:49}. */
    public static final String NAME = "codemode";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return CodemodeDescription.SNIPPET;
    }

    @Override
    public void register(ExtensionContext ctx) {
        ctx.tools().register(new CodemodeTool(ctx, CodemodeExecutor::new));
        ctx.registerToolRenderer(new CodemodeToolRenderer());
    }
}
