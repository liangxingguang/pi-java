package com.pijava.coding.agent.subcommand;

/**
 * Extension package-management subcommands (Phase 3 design §9.4).
 *
 * <p>Phase 3 only enumerates the entry points; the extension system arrives
 * Phase 6. Every command prints a clear not-implemented message and exits
 * with code 2 (never silently ignores the invocation).</p>
 */
public final class PackageCommand {

    private PackageCommand() {}

    /**
     * Dispatch an install/remove/uninstall/update/list subcommand.
     *
     * @param subCommand the matched subcommand name
     * @return process exit code (2 = not implemented)
     */
    public static int run(String subCommand) {
        System.out.println("error: `" + subCommand
            + "` is not implemented yet (extension system lands in Phase 6)");
        return 2;
    }
}
