package com.pijava.coding.agent.extension.mcp;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.pijava.mcp.config.McpExposure;
import com.pijava.mcp.config.McpScope;
import com.pijava.mcp.config.McpServerConfig;
import com.pijava.mcp.config.McpServerEntry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 展示层（{@code index.ts:113-272}）。
 */
class McpServerPresentationTest {

    private static McpServerEntry entry(McpServerConfig config) {
        return new McpServerEntry("files", config, Path.of("mcp.json"), McpScope.GLOBAL, null);
    }

    private static McpServerConfig.Stdio stdio(String command, Map<String, McpExposure> toolExposure) {
        return new McpServerConfig.Stdio(null, null, " A docs server \nsecond line ", toolExposure,
                null, null, command, java.util.List.of("--flag"), null, null);
    }

    // -------------------------------------------------------------- state

    @Test
    void aDisabledServerSaysSoWithoutAConnection() {
        var config = new McpServerConfig.Http(null, null, null, null, false, null,
                "http://x", null, null, null);
        assertThat(McpServerPresentation.describeState(entry(config), null, true))
                .isEqualTo("disabled");
        assertThat(McpServerPresentation.attentionRank(entry(config), null)).isEqualTo(5);
    }

    @Test
    void aServerWithoutAConnectionIsStarting() {
        assertThat(McpServerPresentation.describeState(entry(stdio("npx", null)), null, true))
                .isEqualTo("starting");
        assertThat(McpServerPresentation.attentionRank(entry(stdio("npx", null)), null)).isEqualTo(3);
    }

    @Test
    void needsAuthReadsAsNeedsSignIn() {
        assertThat(McpServerPresentation.describeState("needs-auth", null, 0, 0, true))
                .isEqualTo("needs sign-in");
        assertThat(McpServerPresentation.attentionRank(true, "needs-auth")).isZero();
    }

    @Test
    void aFailureCarriesItsFirstLineOnlyWhenAsked() {
        assertThat(McpServerPresentation.describeState("failed", "boom\nsecond line", 0, 0, true))
                .isEqualTo("failed: boom");
        assertThat(McpServerPresentation.describeState("failed", "boom\nsecond line", 0, 0, false))
                .isEqualTo("failed");
        assertThat(McpServerPresentation.describeState("failed", null, 0, 0, true))
                .isEqualTo("failed: unknown error");
        assertThat(McpServerPresentation.attentionRank(true, "failed")).isEqualTo(1);
    }

    @Test
    void connectedCountsToolsAndResources() {
        assertThat(McpServerPresentation.describeState("connected", null, 0, 0, true))
                .isEqualTo("connected · 0 tools");
        assertThat(McpServerPresentation.describeState("connected", null, 1, 0, true))
                .isEqualTo("connected · 1 tool");
        assertThat(McpServerPresentation.describeState("connected", null, 2, 3, true))
                .isEqualTo("connected · 2 tools · 3 resources");
        assertThat(McpServerPresentation.describeState("connected", null, 1, 1, true))
                .isEqualTo("connected · 1 tool · 1 resource");
        assertThat(McpServerPresentation.attentionRank(true, "connected")).isEqualTo(4);
    }

    @Test
    void connectingAndDisconnectedKeepTheirOwnWords() {
        assertThat(McpServerPresentation.describeState("connecting", null, 0, 0, true))
                .isEqualTo("connecting…");
        assertThat(McpServerPresentation.describeState("disconnected", null, 0, 0, true))
                .isEqualTo("disconnected");
        assertThat(McpServerPresentation.attentionRank(true, "disconnected")).isEqualTo(2);
        assertThat(McpServerPresentation.attentionRank(true, "connecting")).isEqualTo(3);
    }

    @Test
    void theStateAndTheCommandListingWriteDifferently() {
        // The `pi-java mcp list` command writes `connected, 1 tool`; this one writes a middle dot.
        assertThat(McpServerPresentation.describeState("connected", null, 1, 0, true))
                .doesNotContain(",");
    }

    // ---------------------------------------------------------- exposure

    @Test
    void onlyAnExplicitFalseDisablesAServer() {
        var enabledNull = new McpServerConfig.Http(null, null, null, null, null, null,
                "http://x", null, null, null);
        var enabledTrue = new McpServerConfig.Http(null, null, null, null, true, null,
                "http://x", null, null, null);
        assertThat(McpServerPresentation.isEnabled(entry(enabledNull))).isTrue();
        assertThat(McpServerPresentation.isEnabled(entry(enabledTrue))).isTrue();
        assertThat(McpServerPresentation.isEnabled(entry(
                new McpServerConfig.Http(null, null, null, null, false, null,
                        "http://x", null, null, null)))).isFalse();
    }

    @Test
    void theExposureDefaultsToCodemode() {
        assertThat(McpServerPresentation.exposureOf(entry(stdio("npx", null))))
                .isEqualTo(McpExposure.CODEMODE);
        assertThat(McpServerPresentation.exposureOf(entry(new McpServerConfig.Http(
                null, McpExposure.HIDDEN, null, null, null, null, "http://x", null, null, null))))
                .isEqualTo(McpExposure.HIDDEN);
    }

    @Test
    void perToolExposuresJoinTheServersOwn() {
        var config = stdio("npx", Map.of("echo", McpExposure.DIRECT, "read", McpExposure.DEFERRED));
        assertThat(McpServerPresentation.configuredExposures(entry(config)))
                .containsExactlyInAnyOrder(McpExposure.CODEMODE, McpExposure.DIRECT,
                        McpExposure.DEFERRED);
    }

    @Test
    void directAndIndirectToolsAreDifferentQuestions() {
        var direct = stdio("npx", Map.of("echo", McpExposure.DIRECT));
        // The server's own exposure is codemode, so it still has indirect tools.
        assertThat(McpServerPresentation.hasDirectTools(entry(direct))).isTrue();
        assertThat(McpServerPresentation.hasIndirectTools(entry(direct))).isTrue();

        var hidden = new McpServerConfig.Http(null, McpExposure.HIDDEN, null, null, null, null,
                "http://x", null, null, null);
        assertThat(McpServerPresentation.hasDirectTools(entry(hidden))).isFalse();
        assertThat(McpServerPresentation.hasIndirectTools(entry(hidden))).isFalse();

        var deferred = new McpServerConfig.Http(null, McpExposure.DEFERRED, null, null, null, null,
                "http://x", null, null, null);
        assertThat(McpServerPresentation.hasIndirectTools(entry(deferred))).isTrue();
    }

    // -------------------------------------------------------- transport

    @Test
    void theTransportIsTheUrlOrTheCommandLine() {
        assertThat(McpServerPresentation.describeTransport(entry(new McpServerConfig.Http(
                null, null, null, null, null, null, "http://x/mcp", null, null, null))))
                .isEqualTo("http://x/mcp");
        assertThat(McpServerPresentation.describeTransport(entry(stdio("npx", null))))
                .isEqualTo("npx --flag");
        assertThat(McpServerPresentation.describeTransport(entry(new McpServerConfig.Stdio(
                null, null, null, null, null, null, "node", null, null, null))))
                .isEqualTo("node");
    }

    @Test
    void theFirstLineStopsAtTheNewline() {
        assertThat(McpServerPresentation.firstLine("a\nb")).isEqualTo("a");
        assertThat(McpServerPresentation.firstLine("a")).isEqualTo("a");
        assertThat(McpServerPresentation.firstLine("")).isEmpty();
    }

    @Test
    void aScriptNeedsAServerItNamesOrSearchesFor() {
        // A helper call means the script may reach any server, so it counts as needing this one.
        assertThat(McpServerPresentation.scriptNeedsServer("return searchTools()", "files")).isTrue();
        assertThat(McpServerPresentation.scriptNeedsServer("describeNamespace('x')", "files")).isTrue();
        assertThat(McpServerPresentation.scriptNeedsServer("describeTool('t')", "files")).isTrue();
        assertThat(McpServerPresentation.scriptNeedsServer("ALL_TOOLS.map(x => x)", "files")).isTrue();
        // The namespace itself, which sanitizes dashes the same way.
        assertThat(McpServerPresentation.scriptNeedsServer("await mcp__files__echo({})", "files")).isTrue();
        assertThat(McpServerPresentation.scriptNeedsServer("await mcp__my_files__echo({})",
                "my-files")).isTrue();
        // Neither: another server's tools only.
        assertThat(McpServerPresentation.scriptNeedsServer("await mcp__other__echo({})", "files"))
                .isFalse();
        assertThat(McpServerPresentation.scriptNeedsServer("1 + 1", "files")).isFalse();
        // The helper names must be whole words.
        assertThat(McpServerPresentation.scriptNeedsServer("mySearchToolsHelper()", "files"))
                .isFalse();
    }

    @Test
    void theFixedTextsArePinned() {
        assertThat(McpServerPresentation.EXPOSURE_DESCRIPTIONS).hasSize(3)
                .containsEntry(McpExposure.DIRECT, "declared to the model like built-in tools");
        assertThat(McpServerPresentation.MCP_USAGE).isEqualTo(
                "Usage: /mcp, /mcp login [server], /mcp logout [server], /mcp reconnect [server]");
    }
}
