package com.pijava.coding.agent.subcommand;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pijava.mcp.runtime.McpOAuthCredentialStore;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 帮助与未知命令（{@code cli.ts:185-193,256-259}）。
 */
class McpCliHelpTest {

    @TempDir
    Path root;

    private final List<String> out = new ArrayList<>();
    private final List<String> err = new ArrayList<>();

    private int run(String... args) {
        return McpCommand.run(args, new McpCommand.Options(root, root,
                new McpOAuthCredentialStore(root), null, out::add, err::add, false,
                InputStream.nullInputStream(), "pi-java", "0.0.0"));
    }

    @Test
    void noArgumentsPrintsTheHelp() {
        assertThat(run()).isZero();
        assertThat(out).containsExactly(McpCliHelp.HELP);
    }

    @Test
    void helpIsAcceptedThreeWays() {
        for (var args : List.of(new String[] {"help"}, new String[] {"--help"},
                new String[] {"list", "-h"})) {
            out.clear();
            assertThat(run(args)).isZero();
            assertThat(out).containsExactly(McpCliHelp.HELP);
        }
    }

    @Test
    void theHelpNamesEveryCommandAndOption() {
        assertThat(McpCliHelp.HELP)
                .contains("pi-java mcp add <server>")
                .contains("pi-java mcp remove <server> [-l]")
                .contains("pi-java mcp list [--json]")
                .contains("pi-java mcp login <server> [--timeout <seconds>]")
                .contains("pi-java mcp logout <server>")
                .contains("--bearer-token-env-var")
                // pi writes `.pi/` here without interpolating its own constant; pi-java does not.
                .contains("~/.pi-java/agent/mcp.json")
                .contains(".pi-java/mcp.json");
        assertThat(McpCliHelp.HELP).doesNotContain(".pi/mcp.json");
    }

    @Test
    void anUnknownCommandIsReported() {
        assertThat(run("nope")).isEqualTo(1);
        assertThat(err).containsExactly("Unknown mcp command \"nope\".\n" + McpCliHelp.HINT);
    }

    @Test
    void theHintNamesTheRealCommand() {
        assertThat(McpCliHelp.HINT).isEqualTo("Use \"pi-java mcp --help\" for usage.");
        assertThat(McpCliHelp.APP_NAME).isEqualTo("pi-java");
        assertThat(McpCliHelp.CONFIG_DIR_NAME).isEqualTo(".pi-java");
    }

    @Test
    void theBrowserLauncherKnowsEachPlatformsCommand() {
        assertThat(BrowserLauncher.commandFor("Windows 11", "http://x"))
                .containsExactly("rundll32", "url.dll,FileProtocolHandler", "http://x");
        assertThat(BrowserLauncher.commandFor("Mac OS X", "http://x"))
                .containsExactly("open", "http://x");
        assertThat(BrowserLauncher.commandFor("Linux", "http://x"))
                .containsExactly("xdg-open", "http://x");
    }
}
