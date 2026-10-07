package com.pijava.coding.agent.subcommand;

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

import com.pijava.mcp.McpJson;
import com.pijava.mcp.runtime.McpOAuthCredentialStore;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code pi-java mcp list}（{@code cli.ts:434-519}）。
 */
class McpListCommandTest {

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

    private McpCommand.Options options() {
        return new McpCommand.Options(cwd, agentDir, new McpOAuthCredentialStore(agentDir), null,
                out::add, err::add, false, InputStream.nullInputStream(), "pi-java", "0.0.0");
    }

    private int run(String... args) {
        return McpCommand.run(args, options());
    }

    /** Write an {@code mcp.json} with one entry per argument pair. */
    private void configure(String json) throws IOException {
        Files.writeString(agentDir.resolve("mcp.json"), json, StandardCharsets.UTF_8);
    }

    private static String javaBin() {
        return ProcessHandle.current().info().command().orElseThrow();
    }

    /** An entry that runs the stdio fixture as a child process. */
    private static String fixtureServer(String name) {
        return "{ \"mcpServers\": { \"" + name + "\": { \"command\": " + quote(javaBin())
                + ", \"args\": [" + quote("-cp") + ", " + quote(System.getProperty("java.class.path"))
                + ", " + quote(StdioMcpFixture.class.getName()) + "] } } }";
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\") + "\"";
    }

    // --------------------------------------------------------------------- list

    @Test
    void reportsAConnectedServer() throws IOException {
        configure(fixtureServer("files"));
        assertThat(run("list")).isZero();

        assertThat(out).anyMatch(line -> line.equals("files: connected, 1 tool (codemode, global)"));
        assertThat(out).anyMatch(line -> line.startsWith("  " + javaBin()));
        assertThat(out).anyMatch(line -> line.equals("  tools: echo"));
        assertThat(out).anyMatch(line -> line.equals("  resources: 1, URI templates: 1"));
        assertThat(err).isEmpty();
    }

    @Test
    void theSingularAndPluralToolCountsDiffer() throws IOException {
        configure(fixtureServer("files"));
        run("list");
        // The fixture offers exactly one tool.
        assertThat(out.get(0)).contains("connected, 1 tool ");
        assertThat(out.get(0)).doesNotContain("1 tools");
    }

    /** An entry that runs the fixture with {@code no-tools}, so it connects offering nothing. */
    private static String toolLessFixtureServer(String name) {
        return "{ \"mcpServers\": { \"" + name + "\": { \"command\": " + quote(javaBin())
                + ", \"args\": [" + quote("-cp") + ", " + quote(System.getProperty("java.class.path"))
                + ", " + quote(StdioMcpFixture.class.getName()) + ", " + quote("no-tools") + "] } } }";
    }

    @Test
    void aConnectedServerWithNoToolsUsesThePlural() throws IOException {
        configure(toolLessFixtureServer("empty"));
        assertThat(run("list")).isZero();

        assertThat(out.get(0)).isEqualTo("empty: connected, 0 tools (codemode, global)");
        assertThat(out).noneMatch(line -> line.startsWith("  tools:"));
    }

    @Test
    void aDisabledServerIsNotConnected() throws IOException {
        configure("{ \"mcpServers\": { \"off\": { \"command\": " + quote(javaBin())
                + ", \"enabled\": false } } }");
        assertThat(run("list")).isZero();

        assertThat(out.get(0)).isEqualTo("off: disabled (codemode, global)");
    }

    @Test
    void aServerThatCannotConnectFailsTheCommand() throws IOException {
        configure("{ \"mcpServers\": { \"bad\": { \"command\": \"definitely-not-a-command\" } } }");
        assertThat(run("list")).isEqualTo(1);

        assertThat(out).anyMatch(line -> line.startsWith("bad: failed (codemode, global)"));
        assertThat(out).anyMatch(line -> line.contains("definitely-not-a-command"));
    }

    @Test
    void aProjectOverrideIsShown() throws IOException {
        configure(fixtureServer("files"));
        Files.createDirectories(cwd.resolve(".pi-java"));
        Files.writeString(cwd.resolve(".pi-java").resolve("mcp.json"),
                "{ \"mcpServers\": { \"files\": { \"enabled\": false, \"exposure\": \"direct\" } } }",
                StandardCharsets.UTF_8);

        run("list");
        assertThat(out).anyMatch(line -> line.startsWith("  project override: "));
    }

    @Test
    void aPerToolExposureIsListedInBrackets() throws IOException {
        configure("{ \"mcpServers\": { \"files\": { \"command\": " + quote(javaBin())
                + ", \"args\": [" + quote("-cp") + ", " + quote(System.getProperty("java.class.path"))
                + ", " + quote(StdioMcpFixture.class.getName()) + "]"
                + ", \"toolExposure\": { \"echo\": \"hidden\" } } } }");
        assertThat(run("list")).isZero();

        assertThat(out).anyMatch(line -> line.equals("  tools: echo [hidden]"));
    }

    @Test
    void aConfigErrorFailsTheCommandAndIsPrinted() throws IOException {
        configure("{ \"mcpServers\": { \"bad\": { \"url\": \"ftp://x\" } } }");
        assertThat(run("list")).isEqualTo(1);

        assertThat(out).anyMatch(line -> line.startsWith("config error: "));
    }

    @Test
    void anEmptyConfigurationSaysWhereToAddServers() {
        assertThat(run("list")).isZero();
        assertThat(out).containsExactly("No MCP servers configured. Add them to "
                + agentDir.resolve("mcp.json") + " or .pi-java/mcp.json.");
    }

    @Test
    void jsonCarriesEveryFieldAndTheSameExitCode() throws IOException {
        configure(fixtureServer("files"));
        assertThat(run("list", "--json")).isZero();

        var payload = McpJson.mapper().readTree(String.join("\n", out));
        var server = payload.path("servers").get(0);
        assertThat(server.path("name").asText()).isEqualTo("files");
        assertThat(server.path("scope").asText()).isEqualTo("global");
        assertThat(server.path("enabled").asBoolean()).isTrue();
        assertThat(server.path("exposure").asText()).isEqualTo("codemode");
        assertThat(server.path("state").asText()).isEqualTo("connected");
        assertThat(server.path("tools").get(0).asText()).isEqualTo("echo");
        assertThat(server.path("resources").asInt()).isEqualTo(1);
        assertThat(server.path("resourceTemplates").asInt()).isEqualTo(1);
        assertThat(server.has("error")).isFalse();
        assertThat(payload.path("errors")).isEmpty();
        assertThat(payload.has("note")).isFalse();
    }

    @Test
    void jsonOmitsTheOptionalKeysWhenTheyAreAbsent() throws IOException {
        configure("{ \"mcpServers\": { \"off\": { \"command\": \"x\", \"enabled\": false } } }");
        run("list", "--json");

        var server = McpJson.mapper().readTree(String.join("\n", out)).path("servers").get(0);
        assertThat(server.has("override")).isFalse();
        assertThat(server.has("toolExposure")).isFalse();
        assertThat(server.has("resources")).isFalse();
        assertThat(server.has("resourceTemplates")).isFalse();
        assertThat(server.has("error")).isFalse();
    }

    @Test
    void jsonFailsTheSameWayAndCarriesErrors() throws IOException {
        configure("{ \"mcpServers\": { \"bad\": { \"url\": \"ftp://x\" } } }");
        assertThat(run("list", "--json")).isEqualTo(1);
        var payload = McpJson.mapper().readTree(String.join("\n", out));
        assertThat(payload.path("errors")).isNotEmpty();
    }

    @Test
    void listTakesNoPositionalArguments() {
        assertThat(run("list", "extra")).isEqualTo(1);
        assertThat(err.get(0)).startsWith("Usage: pi-java mcp list [--json]");
    }

    @Test
    void anUnreadableProjectFileIsNotedWhenTheProjectIsUntrusted() throws IOException {
        Files.writeString(agentDir.resolve("settings.json"),
                "{\"defaultProjectTrust\": \"never\"}", StandardCharsets.UTF_8);
        Files.createDirectories(cwd.resolve(".pi-java"));
        Files.writeString(cwd.resolve(".pi-java").resolve("mcp.json"), "{ \"mcpServers\": {} }",
                StandardCharsets.UTF_8);

        run("list");
        assertThat(out).anyMatch(line -> line.contains("is ignored because the project is not trusted"));
    }
}
