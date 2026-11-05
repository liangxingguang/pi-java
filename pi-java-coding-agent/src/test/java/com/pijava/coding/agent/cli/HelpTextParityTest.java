package com.pijava.coding.agent.cli;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** L5 W10：pi args.ts:294-345,463-466 的帮助文案（品牌 pi-java）。 */
class HelpTextParityTest {

    @Test
    void sessionAndExportLinesMatchPi() {
        String help = HelpText.text();
        assertThat(help)
            .contains("  --session <path|id>            Open a session by file path or full/partial ID")
            .contains("  --session-id <id>              Use the current directory's existing session"
                + " with the ID, creating it otherwise")
            .contains("  --fork <path|id>               Fork a session by file path or full/partial ID"
                + " into the current directory")
            .contains("  --session-dir <dir>            Directory for session storage and lookup")
            .contains("  --no-session                   Don't save session (ephemeral)")
            .contains("  --name, -n <name>              Set session display name")
            .contains("  --export <file> [output]       Export session file to HTML and exit")
            .contains("  --provider <name>              Provider to search for --model (requires --model)")
            .contains("  --models <patterns>            Comma-separated model patterns for Ctrl+P cycling")
            .contains("                                 Supports globs (anthropic/*, *sonnet*) and fuzzy matching");
    }

    @Test
    void selfUpdateCommandEnvironmentLineMatchesPi() {
        String help = HelpText.text();
        assertThat(help).contains(
            "  PI_SELF_UPDATE_COMMAND           - Command pi-java update runs to update pi-java"
                + " itself ({packageName}, {version}, {installSpec})");
    }

    @Test
    void commandsSectionMatchesPi() {
        String help = HelpText.text();
        assertThat(help)
            .contains("  pi-java install <source> [-l]     Install extension source and add to settings")
            .contains("  pi-java uninstall <source> [-l]   Alias for remove")
            .contains("  pi-java update [source|self|pi]   Update pi-java, extensions, or model catalogs")
            .contains("  pi-java list                      List installed extensions from settings")
            .contains("  pi-java <command> --help          Show help for"
                + " install/remove/uninstall/update/list/config/auth/mcp");
    }
}
