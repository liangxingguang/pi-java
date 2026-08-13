package com.pijava.coding.agent;

import java.util.ServiceLoader;

import com.pijava.coding.agent.cli.Args;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.cli.HelpText;
import com.pijava.coding.agent.cli.ListModelsCommand;
import com.pijava.coding.agent.cli.Version;
import com.pijava.coding.agent.modes.PrintMode;
import com.pijava.coding.agent.spi.TuiEntryPoint;
import com.pijava.coding.agent.subcommand.SubcommandHandler;

/**
 * {@code pi-java} CLI entry point (Phase 3 design §9.5).
 */
public final class Main {

    private Main() {}

    /** CLI entry point. */
    public static void main(String[] args) {
        System.exit(run(args));
    }

    /** Run the CLI and return the process exit code (testable). */
    public static int run(String[] args) {
        var subcommand = SubcommandHandler.matches(args);
        if (subcommand != null) {
            return SubcommandHandler.dispatch(subcommand, args);
        }

        var parsed = ArgsParser.parse(args);
        if (hasErrors(parsed)) {
            printDiagnostics(parsed);
            return 2;
        }
        if (parsed.help()) {
            System.out.print(HelpText.text());
            return 0;
        }
        if (parsed.version()) {
            System.out.println(Version.VERSION);
            return 0;
        }
        if (parsed.listModels() != null) {
            return ListModelsCommand.run(parsed.listModels());
        }
        if (parsed.mode() != null && !"text".equals(parsed.mode())) {
            System.err.println("error: --mode " + parsed.mode()
                + " is not implemented yet (json/rpc land in Phase 6)");
            return 2;
        }
        if (parsed.export() != null) {
            System.err.println(
                "error: --export is not implemented yet (HTML renderer lands in Phase 6)");
            return 2;
        }
        if (parsed.print()) {
            return PrintMode.run(parsed.messages(), parsed);
        }

        // Interactive mode (default): discover the TUI via ServiceLoader.
        var entry = ServiceLoader.load(TuiEntryPoint.class).findFirst();
        if (entry.isEmpty()) {
            System.err.println(
                "error: interactive mode requires pi-java-tui on the classpath");
            return 1;
        }
        return entry.get().runInteractive(parsed);
    }

    private static boolean hasErrors(Args args) {
        return args.diagnostics().stream()
            .anyMatch(d -> "error".equals(d.type()));
    }

    private static void printDiagnostics(Args args) {
        for (var diagnostic : args.diagnostics()) {
            System.err.println(diagnostic.type() + ": " + diagnostic.message());
        }
    }
}
