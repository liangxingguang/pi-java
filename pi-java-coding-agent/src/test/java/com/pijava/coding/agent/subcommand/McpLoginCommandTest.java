package com.pijava.coding.agent.subcommand;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pijava.mcp.runtime.McpOAuthCredentialStore;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code pi-java mcp login} 与 {@code mcp logout} 的前半段（{@code cli.ts:218-255,521-577}）。
 */
class McpLoginCommandTest {

    @TempDir
    Path root;

    private Path cwd;
    private Path agentDir;
    private final List<String> out = new ArrayList<>();
    private final List<String> err = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        cwd = root.resolve("project");
        agentDir = root.resolve("agent");
        Files.createDirectories(cwd);
        Files.createDirectories(agentDir);
    }

    private int run(String... args) {
        return McpCommand.run(args, new McpCommand.Options(cwd, agentDir,
                new McpOAuthCredentialStore(agentDir), url -> out.add("opened " + url),
                out::add, err::add, false, InputStream.nullInputStream(), "pi-java", "0.0.0"));
    }

    private void configure(String json) throws IOException {
        Files.writeString(agentDir.resolve("mcp.json"), json, StandardCharsets.UTF_8);
    }

    @Test
    void anUnknownServerListsTheConfiguredOnes() throws IOException {
        configure("{ \"mcpServers\": { \"files\": { \"url\": \"http://x\" } } }");
        assertThat(run("login", "nope")).isEqualTo(1);
        assertThat(err).containsExactly(
                "No MCP server named \"nope\". Configured: files.");
    }

    @Test
    void anUnknownServerWithNothingConfiguredSaysNone() {
        assertThat(run("login", "nope")).isEqualTo(1);
        assertThat(err).containsExactly("No MCP server named \"nope\". Configured: none.");
    }

    @Test
    void aStdioServerCannotUseOAuth() throws IOException {
        configure("{ \"mcpServers\": { \"docs\": { \"command\": \"npx\" } } }");
        assertThat(run("login", "docs")).isEqualTo(1);
        assertThat(err).containsExactly("MCP server \"docs\" does not use OAuth. Only HTTP servers"
                + " without an Authorization header do.");
    }

    @Test
    void aServerWithAnAuthorizationHeaderIsNotOAuthEither() throws IOException {
        configure("{ \"mcpServers\": { \"files\": { \"url\": \"http://x\","
                + " \"headers\": { \"Authorization\": \"Bearer t\" } } } }");
        assertThat(run("login", "files")).isEqualTo(1);
        assertThat(err.get(0)).contains("does not use OAuth");
    }

    @Test
    void loginNeedsExactlyOneServerName() throws IOException {
        configure("{ \"mcpServers\": {} }");
        assertThat(run("login")).isEqualTo(1);
        assertThat(err.get(0)).startsWith("Usage: pi-java mcp login <server>");
    }

    @Test
    void anUnreachableOAuthServerReportsWhyItCouldNotConnect() throws IOException {
        configure("{ \"mcpServers\": { \"files\": { \"url\": \"ftp://x\" } } }");
        assertThat(run("login", "files")).isEqualTo(1);
        assertThat(err).hasSize(1);
    }

    @Test
    void theTimeoutMustBeAPositiveNumber() throws IOException {
        configure("{ \"mcpServers\": { \"files\": { \"url\": \"http://127.0.0.1:1/mcp\" } } }");
        assertThat(run("login", "files", "--timeout", "0")).isEqualTo(1);
        assertThat(err).containsExactly("--timeout must be a positive number of seconds.");

        err.clear();
        assertThat(run("login", "files", "--timeout", "abc")).isEqualTo(1);
        assertThat(err).containsExactly("--timeout must be a positive number of seconds.");
    }

    @Test
    void logoutNeedsAStoredServerAndSaysSoWhenThereIsNone() throws IOException {
        configure("{ \"mcpServers\": { \"files\": { \"url\": \"http://x\" } } }");
        assertThat(run("logout", "files")).isZero();
        assertThat(out).containsExactly("No stored credentials for MCP server \"files\".");
        assertThat(err).isEmpty();
    }

    @Test
    void logoutOnANonOAuthServerFailsLikeLogin() throws IOException {
        configure("{ \"mcpServers\": { \"docs\": { \"command\": \"npx\" } } }");
        assertThat(run("logout", "docs")).isEqualTo(1);
        assertThat(err.get(0)).contains("does not use OAuth");
    }

    @Test
    void logoutIgnoresTheTimeoutOption() throws IOException {
        // `logout` takes no options, so --timeout is an unknown one.
        configure("{ \"mcpServers\": { \"files\": { \"url\": \"http://x\" } } }");
        assertThat(run("logout", "files", "--timeout", "5")).isEqualTo(1);
        assertThat(err.get(0)).startsWith("Unknown option --timeout.");
    }

    @Test
    void aPastedUrlNeedsATerminalAndNoInjectedBrowser() {
        // pi: `process.stdin.isTTY === true && options.openUrl === undefined`.
        assertThat(McpCommandSupport.interactive(options(true, null))).isTrue();
        assertThat(McpCommandSupport.interactive(options(false, null))).isFalse();
        assertThat(McpCommandSupport.interactive(options(true, url -> { }))).isFalse();
        assertThat(McpCommandSupport.interactive(options(false, url -> { }))).isFalse();
    }

    private McpCommand.Options options(boolean terminal, java.util.function.Consumer<String> openUrl) {
        return new McpCommand.Options(cwd, agentDir, new McpOAuthCredentialStore(agentDir), openUrl,
                out::add, err::add, terminal, InputStream.nullInputStream(), "pi-java", "0.0.0");
    }

    @Test
    void theSshPathPastesTheRedirectUrlAndOpensNothing() throws IOException {
        // No terminal and an injected openUrl: the browser is told the URL, stdin is not read.
        configure("{ \"mcpServers\": { \"files\": { \"url\": \"http://127.0.0.1:1/mcp\" } } }");
        var options = new McpCommand.Options(cwd, agentDir, new McpOAuthCredentialStore(agentDir),
                url -> out.add("opened " + url), out::add, err::add, false,
                new ByteArrayInputStream("http://127.0.0.1/callback?code=x&state=y\n"
                        .getBytes(StandardCharsets.UTF_8)),
                "pi-java", "0.0.0");
        assertThat(McpCommand.run(new String[] {"login", "files", "--timeout", "1"}, options))
                .isEqualTo(1);
        // The connect failed before any sign-in, so nothing was opened.
        assertThat(out).noneMatch(line -> line.startsWith("opened "));
    }
}
