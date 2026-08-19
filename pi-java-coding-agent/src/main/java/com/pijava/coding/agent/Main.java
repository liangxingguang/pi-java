package com.pijava.coding.agent;

import java.util.ServiceLoader;

import com.pijava.coding.agent.cli.Args;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.cli.HelpText;
import com.pijava.coding.agent.cli.ListModelsCommand;
import com.pijava.coding.agent.cli.Version;
import com.pijava.coding.agent.core.Logging;
import com.pijava.coding.agent.modes.PrintMode;
import com.pijava.coding.agent.rpc.RpcMode;
import com.pijava.coding.agent.spi.TuiEntryPoint;
import com.pijava.coding.agent.subcommand.SubcommandHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code pi-java} CLI entry point (Phase 3 design §9.5).
 */
public final class Main {

    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

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
        Logging.configure(parsed.debug(), !parsed.print());
        LOG.debug("CLI args parsed: mode={} print={} debug={}",
            parsed.mode(), parsed.print(), parsed.debug());
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
        if (parsed.mode() != null && "rpc".equals(parsed.mode())) {
            return RpcMode.run(System.in, System.out, parsed);
        }
        if (parsed.mode() != null && !"text".equals(parsed.mode())) {
            System.err.println("error: --mode " + parsed.mode()
                + " is not implemented yet (json lands in Phase 6)");
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
