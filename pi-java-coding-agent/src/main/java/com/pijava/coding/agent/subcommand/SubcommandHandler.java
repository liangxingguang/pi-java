package com.pijava.coding.agent.subcommand;

import java.util.Set;

/**
 * Top-level subcommand dispatch, aligned with pi's {@code main.ts}
 * (Phase 3 design §9.4).
 */
public final class SubcommandHandler {

    private static final Set<String> SUBCOMMANDS = Set.of(
        "install", "remove", "uninstall", "update", "list", "config", "auth");

    private SubcommandHandler() {}

    /**
     * Detect a subcommand in the argument list.
     *
     * @return the subcommand name, or {@code null} when args start with flags
     *         or positional messages
     */
    public static String matches(String[] args) {
        if (args.length == 0) {
            return null;
        }
        var first = args[0];
        return SUBCOMMANDS.contains(first) ? first : null;
    }

    /**
     * Dispatch a detected subcommand.
     *
     * @param subCommand the subcommand name from {@link #matches}
     * @param args       the full CLI argument list
     * @return process exit code
     */
    public static int dispatch(String subCommand, String[] args) {
        var subArgs = java.util.Arrays.copyOfRange(args, 1, args.length);
        return switch (subCommand) {
            case "auth" -> AuthCommand.run(subArgs);
            case "install", "remove", "uninstall", "update", "list" ->
                PackageCommand.run(subCommand, subArgs);
            case "config" -> ConfigCommand.run(subArgs);
            default -> 2;
        };
    }
}
