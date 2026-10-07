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
 * {@code pi-java mcp add} 与 {@code mcp remove}（{@code cli.ts:277-432}）。
 */
class McpAddRemoveCommandTest {

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

    private Path globalConfig() {
        return agentDir.resolve("mcp.json");
    }

    private String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static String usageHint() {
        return McpCliHelp.HINT;
    }

    // ---------------------------------------------------------------------- add

    @Test
    void addsAnHttpServer() throws IOException {
        assertThat(run("add", "files", "--url", "http://example.test/mcp")).isZero();

        var config = read(globalConfig());
        assertThat(config).contains("\"url\": \"http://example.test/mcp\"");
        assertThat(out.get(0)).isEqualTo("Added global MCP server \"files\" in " + globalConfig() + ".");
        assertThat(out.get(1)).isEqualTo(
                "Check it with: pi-java mcp list. If it requires sign-in: pi-java mcp login files");
        assertThat(err).isEmpty();
    }

    @Test
    void addsAStdioServerWithItsCommandAndArguments() throws IOException {
        assertThat(run("add", "docs", "--", "npx", "-y", "docs-server")).isZero();

        var config = read(globalConfig());
        assertThat(config).contains("\"command\": \"npx\"");
        assertThat(config).contains("\"args\"");
        assertThat(config).contains("-y");
        // A stdio server cannot need OAuth, so the sign-in hint is absent.
        assertThat(out.get(1)).isEqualTo("Check it with: pi-java mcp list");
    }

    @Test
    void aUrlAndACommandTogetherAreRejected() {
        assertThat(run("add", "files", "--url", "http://x", "--", "npx")).isEqualTo(1);
        assertThat(err).hasSize(1);
        assertThat(err.get(0)).startsWith("Usage: pi-java mcp add <server> [options]")
                .contains(usageHint());
    }

    @Test
    void neitherAUrlNorACommandIsRejected() {
        assertThat(run("add", "files")).isEqualTo(1);
        assertThat(err.get(0)).startsWith("Usage: pi-java mcp add");
    }

    @Test
    void anHttpOnlyOptionOnAStdioServerIsRejected() {
        assertThat(run("add", "--header", "A=1", "docs", "npx")).isEqualTo(1);
        assertThat(err).containsExactly("--header only applies to HTTP servers (--url).");
    }

    @Test
    void aStdioOnlyOptionOnAnHttpServerIsRejected() {
        assertThat(run("add", "--env", "A=1", "files", "--url", "http://x")).isEqualTo(1);
        assertThat(err).containsExactly("--env only applies to stdio servers.");
    }

    @Test
    void theBearerTokenOptionWritesTheLiteralReference() throws IOException {
        assertThat(run("add", "files", "--url", "http://x", "--bearer-token-env-var", "TOKEN"))
                .isZero();

        // The ${NAME} is resolved when connecting, not when writing.
        assertThat(read(globalConfig())).contains("\"Authorization\": \"Bearer ${TOKEN}\"");
    }

    @Test
    void theCallbackPortIsWrittenAsAnInteger() throws IOException {
        assertThat(run("add", "files", "--url", "http://x", "--oauth-callback-port", "8080"))
                .isZero();

        // `${8080.0}` would not round-trip through the validator.
        assertThat(read(globalConfig())).contains("\"callbackPort\": 8080");
    }

    @Test
    void aNonNumericCallbackPortIsRejectedByValidation() {
        assertThat(run("add", "files", "--url", "http://x", "--oauth-callback-port", "abc"))
                .isEqualTo(1);
        assertThat(err).hasSize(1);
        assertThat(err.get(0)).contains("callbackPort");
    }

    @Test
    void exposureAndDescriptionAreWritten() throws IOException {
        assertThat(run("add", "files", "--url", "http://x",
                "--exposure", "direct", "--description", "Docs server")).isZero();

        var config = read(globalConfig());
        assertThat(config).contains("\"exposure\": \"direct\"");
        assertThat(config).contains("\"description\": \"Docs server\"");
    }

    @Test
    void theOauthObjectOnlyCarriesWhatWasGiven() throws IOException {
        assertThat(run("add", "files", "--url", "http://x", "--oauth-client-id", "abc")).isZero();

        var config = read(globalConfig());
        assertThat(config).contains("\"oauth\"");
        assertThat(config).contains("\"clientId\": \"abc\"");
        assertThat(config).doesNotContain("clientSecret");
        assertThat(config).doesNotContain("callbackPort");
    }

    @Test
    void aHeaderWithoutAnEqualsIsRejected() throws IOException {
        assertThat(run("add", "files", "--url", "http://x", "--header", "nope")).isEqualTo(1);
        assertThat(err).containsExactly("--header expects KEY=VALUE, got \"nope\".");
        assertThat(Files.exists(globalConfig())).isFalse();
    }

    @Test
    void aServerDefinedTwiceIsReplaced() throws IOException {
        run("add", "files", "--url", "http://one");
        out.clear();
        assertThat(run("add", "files", "--url", "http://two")).isZero();

        assertThat(out.get(0)).startsWith("Replaced global MCP server \"files\"");
        var config = read(globalConfig());
        assertThat(config).contains("http://two").doesNotContain("http://one");
    }

    @Test
    void theLocalFlagWritesTheProjectFile() throws IOException {
        assertThat(run("add", "files", "--url", "http://x", "-l")).isZero();

        var project = cwd.resolve(".pi-java").resolve("mcp.json");
        assertThat(Files.exists(project)).isTrue();
        assertThat(out.get(0)).startsWith("Added project MCP server \"files\"");
        assertThat(Files.exists(globalConfig())).isFalse();
    }

    @Test
    void anUntrustedProjectIsCalledOut() throws IOException {
        Files.writeString(agentDir.resolve("settings.json"),
                "{\"defaultProjectTrust\": \"never\"}", StandardCharsets.UTF_8);
        assertThat(run("add", "files", "--url", "http://x", "-l")).isZero();

        assertThat(out.get(1)).isEqualTo("The project is not trusted, so "
                + cwd.resolve(".pi-java").resolve("mcp.json")
                + " is ignored until you start pi-java in the project and trust it.");
    }

    @Test
    void anInvalidExposureIsReportedByTheValidator() {
        assertThat(run("add", "files", "--url", "http://x", "--exposure", "nope")).isEqualTo(1);
        assertThat(err).hasSize(1);
        assertThat(err.get(0)).contains("exposure");
    }

    @Test
    void aServerNameWithBadCharactersIsRejected() {
        assertThat(run("add", "bad name", "--url", "http://x")).isEqualTo(1);
        assertThat(err).hasSize(1);
    }

    // ------------------------------------------------------------------- remove

    @Test
    void removesAServerItJustAdded() throws IOException {
        run("add", "files", "--url", "http://x");
        out.clear();

        assertThat(run("remove", "files")).isZero();
        assertThat(out).containsExactly(
                "Removed global MCP server \"files\" from " + globalConfig() + ".");
        assertThat(read(globalConfig())).contains("mcpServers").doesNotContain("files");
    }

    @Test
    void removingSomethingThatIsNotThereSaysWhereItIs() {
        assertThat(run("remove", "files")).isEqualTo(1);
        assertThat(err).hasSize(1);
        assertThat(err.get(0)).startsWith("No global MCP server named \"files\" in ");
    }

    @Test
    void removingFromTheWrongScopePointsAtTheOtherOne() {
        run("add", "files", "--url", "http://x", "-l");
        err.clear();

        assertThat(run("remove", "files")).isEqualTo(1);
        assertThat(err.get(0)).contains("It is defined in").contains("; use --local.");
    }

    @Test
    void removingWithoutANameIsAUsageError() {
        assertThat(run("remove")).isEqualTo(1);
        assertThat(err.get(0)).startsWith("Usage: pi-java mcp remove <server> [-l]");
    }

    @Test
    void aJsonRoundTripKeepsTheMcpShape() throws IOException {
        run("add", "docs", "--", "npx", "-y", "pkg");
        var parsed = McpJson.mapper().readTree(read(globalConfig()));
        assertThat(parsed.path("mcpServers").has("docs")).isTrue();
        assertThat(parsed.path("mcpServers").path("docs").path("command").asText())
                .isEqualTo("npx");
    }
}
