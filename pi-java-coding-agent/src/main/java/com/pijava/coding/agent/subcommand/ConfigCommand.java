package com.pijava.coding.agent.subcommand;

/**
 * {@code pi-java config} subcommand (Phase 3 design §9.4).
 *
 * <p>The TUI resource-switch screen arrives Phase 6; Phase 3 reports the
 * not-implemented status explicitly.</p>
 */
public final class ConfigCommand {

    private ConfigCommand() {}

    /** Run the config subcommand. */
    public static int run() {
        System.out.println(
            "error: `config` is not implemented yet (TUI resource switches land in Phase 6)");
        return 2;
    }
}
