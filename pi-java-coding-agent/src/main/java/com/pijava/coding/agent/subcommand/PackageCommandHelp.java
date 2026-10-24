package com.pijava.coding.agent.subcommand;

/**
 * 包管理子命令的 Usage/帮助文本（批 19 T4）——pi {@code getPackageCommandUsage}/
 * {@code printPackageCommandHelp}（package-manager-cli.ts:287-300/:317-395）对位；
 * {@code APP_NAME}→{@code pi-java}、{@code CONFIG_DIR_NAME}→{@code .pi-java} 既定翻译，
 * chalk 色裁（D116）。
 */
final class PackageCommandHelp {

    private PackageCommandHelp() {}

    /** pi {@code getPackageCommandUsage}（:287-300）逐字（APP_NAME 翻译后）。 */
    static String usage(String command) {
        return switch (command) {
            case "install" -> "pi-java install <source> [-l] [--approve|--no-approve]";
            case "remove" -> "pi-java remove <source> [-l] [--approve|--no-approve]";
            case "update" -> "pi-java update [source|self|pi] "
                + "[--self|--extensions|--models|--all] [--extension <source>] "
                + "[--approve|--no-approve] [--force]";
            default -> "pi-java list [--approve|--no-approve]";
        };
    }

    /** pi {@code printConfigCommandHelp}（:302-315）逐字（色裁、名翻译）。 */
    static void printConfig() {
        System.out.println("""
            Usage:
              pi-java config [-l] [--approve|--no-approve]

            Open the resource configuration TUI to enable or disable package resources.
            Without -l, starts in global settings (~/.pi-java/agent/settings.json).
            Press Tab in the TUI to switch between global and project-local modes.

            Options:
              -l, --local       Edit project overrides (.pi-java/settings.json)
              -a, --approve     Trust project-local files for this command with -l
              -na, --no-approve Ignore project-local files for this command with -l
            """);
    }

    /** pi {@code printPackageCommandHelp}（:317-395）逐字（色裁、名翻译）。 */
    static void print(String command) {
        switch (command) {
            case "install" -> System.out.println("""
                Usage:
                  pi-java install <source> [-l] [--approve|--no-approve]

                Install a package and add it to settings.

                Options:
                  -l, --local       Install project-locally (.pi-java/settings.json)
                  -a, --approve     Trust project-local files for this command
                  -na, --no-approve Ignore project-local files for this command

                Examples:
                  pi-java install npm:@foo/bar
                  pi-java install git:github.com/user/repo
                  pi-java install git:git@github.com:user/repo
                  pi-java install https://github.com/user/repo
                  pi-java install ssh://git@github.com/user/repo
                  pi-java install ./local/path
                """);
            case "remove" -> System.out.println("""
                Usage:
                  pi-java remove <source> [-l] [--approve|--no-approve]

                Remove a package and its source from settings.
                Alias: pi-java uninstall <source> [-l]

                Options:
                  -l, --local       Remove from project settings (.pi-java/settings.json)
                  -a, --approve     Trust project-local files for this command
                  -na, --no-approve Ignore project-local files for this command

                Examples:
                  pi-java remove npm:@foo/bar
                  pi-java uninstall npm:@foo/bar
                """);
            case "update" -> System.out.println("""
                Usage:
                  pi-java update [source|self|pi] [--self|--extensions|--models|--all] [--extension <source>] [--approve|--no-approve] [--force]

                Update pi, installed packages, or model catalogs.

                Options:
                  --self                  Update pi only (default when no target is given)
                  --extensions            Update installed packages only
                  --models                Refresh model catalogs only
                  --all                   Update pi and installed packages
                  --extension <source>    Update one package only
                  -a, --approve           Trust project-local files for this command
                  -na, --no-approve       Ignore project-local files for this command
                  --force                 Reinstall pi even if the current version is latest

                Short forms:
                  pi-java update                Update pi only
                  pi-java update --all          Update pi and all extensions
                  pi-java update --models       Refresh model catalogs only
                  pi-java update <source>       Update one package
                  pi-java update pi             Update pi only (self works as alias to pi)
                """);
            default -> System.out.println("""
                Usage:
                  pi-java list [--approve|--no-approve]

                List installed packages from user and project settings.

                Options:
                  -a, --approve      Trust project-local files for this command
                  -na, --no-approve  Ignore project-local files for this command
                """);
        }
    }
}
