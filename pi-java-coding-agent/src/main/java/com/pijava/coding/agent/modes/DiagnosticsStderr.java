package com.pijava.coding.agent.modes;

import java.io.PrintStream;
import java.util.List;

import com.pijava.coding.agent.cli.Args;

/**
 * Writes session diagnostics to stderr (pi {@code modes/diagnostics-stderr.ts},
 * anchor {@code a00a5048d}, L5 W5).
 *
 * <p>The RPC line protocol owns stdout, and the TUI owns the terminal, so startup
 * and replacement diagnostics go to stderr. pi colors by type; Java prints the
 * type prefix (narrow difference D83 family).</p>
 */
public final class DiagnosticsStderr {

    private DiagnosticsStderr() {}

    /** Print every diagnostic as {@code <type>: <message>}. */
    public static void write(List<Args.ArgDiagnostic> diagnostics) {
        write(diagnostics, System.err);
    }

    /** Testable form. */
    public static void write(List<Args.ArgDiagnostic> diagnostics, PrintStream out) {
        for (var diagnostic : diagnostics) {
            out.println(diagnostic.type() + ": " + diagnostic.message());
        }
    }
}
