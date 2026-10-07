package com.pijava.mcp.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;

import com.pijava.mcp.McpJson;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code mcp.json} 的增删改与落盘（{@code config.ts:159-242}）。
 */
class McpConfigWriterTest {

    @TempDir
    Path root;

    private Path file;

    @BeforeEach
    void setUp() {
        file = root.resolve("agent").resolve("mcp.json");
    }

    private static JsonNode tree(Path path) throws IOException {
        return McpJson.mapper().readTree(Files.readString(path));
    }

    private static JsonNode json(String text) throws Exception {
        return McpJson.mapper().readTree(text);
    }

    private static McpServerConfig stdio(String command, List<String> args) {
        return new McpServerConfig.Stdio(null, null, null, null, null, null, command, args, null, null);
    }

    @Test
    void addCreatesTheFileAndReportsReplacement() throws Exception {
        var docs = stdio("node", List.of("server.js"));
        assertThat(McpConfig.add(file, "docs", docs)).isFalse();
        assertThat(tree(file)).isEqualTo(json("{\"mcpServers\":{\"docs\":{\"command\":\"node\","
                + "\"args\":[\"server.js\"]}}}"));

        assertThat(McpConfig.add(file, "docs", stdio("bun", null))).isTrue();
        assertThat(tree(file).get("mcpServers").get("docs").get("command").textValue()).isEqualTo("bun");
        assertThat(tree(file).get("mcpServers").get("docs").size()).isEqualTo(1);
    }

    @Test
    void removeReportsWhetherAnEntryWasRemoved() throws Exception {
        assertThat(McpConfig.remove(file, "docs")).isFalse();

        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"mcpServers\":{\"other\":{\"command\":\"x\"}}}");
        assertThat(McpConfig.remove(file, "docs")).isFalse();
        // 没改动 ⇒ 不重写文件。
        assertThat(Files.readString(file)).isEqualTo("{\"mcpServers\":{\"other\":{\"command\":\"x\"}}}");

        assertThat(McpConfig.remove(file, "other")).isTrue();
        assertThat(tree(file)).isEqualTo(json("{\"mcpServers\":{}}"));
    }

    @Test
    void updateRejectsUnknownServersWithoutAnOverride() throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"mcpServers\":{}}");

        assertThatThrownBy(() -> McpConfig.update(file, "docs", new McpConfigPatch(false, null)))
                .isInstanceOf(McpConfigError.class)
                .hasMessage(file + " does not define MCP server \"docs\"");
        assertThat(Files.readString(file)).isEqualTo("{\"mcpServers\":{}}");
    }

    @Test
    void updateAddsAnOverrideEntryThatKeepsDefaultValues() throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"mcpServers\":{}}");

        McpConfig.update(file, "tools", new McpConfigPatch(true, null), true);
        // override 条目保留默认值 ⇒ enabled:true 照写（config.ts:182-183）。
        assertThat(tree(file)).isEqualTo(json("{\"mcpServers\":{\"tools\":{\"enabled\":true}}}"));
    }

    @Test
    void updateDropsDefaultValuesOfFullEntries() throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"mcpServers\":{\"tools\":{\"command\":\"x\",\"enabled\":false,"
                + "\"exposure\":\"direct\"}}}");

        McpConfig.update(file, "tools", new McpConfigPatch(true, McpExposure.CODEMODE));
        assertThat(tree(file)).isEqualTo(json("{\"mcpServers\":{\"tools\":{\"command\":\"x\"}}}"));

        McpConfig.update(file, "tools", new McpConfigPatch(false, McpExposure.HIDDEN));
        assertThat(tree(file)).isEqualTo(json("{\"mcpServers\":{\"tools\":{\"command\":\"x\","
                + "\"enabled\":false,\"exposure\":\"hidden\"}}}"));
    }

    @Test
    void keepsTheIndentationAndTheRestOfTheFile() throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                {
                    "autoEnableCodemode": false,
                    "unknown": {"keep": [1, 2]},
                    "mcpServers": {
                        "tools": {"command": "x"}
                    }
                }
                """);

        McpConfig.update(file, "tools", new McpConfigPatch(false, null));
        assertThat(Files.readString(file)).isEqualTo("""
                {
                    "autoEnableCodemode": false,
                    "unknown": {
                        "keep": [
                            1,
                            2
                        ]
                    },
                    "mcpServers": {
                        "tools": {
                            "command": "x",
                            "enabled": false
                        }
                    }
                }
                """);
    }

    @Test
    void keepsTabIndentationAndAddsMissingFiles() throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\n\t\"mcpServers\": {\n\t\t\"a\": {\"command\": \"x\"}\n\t}\n}\n");

        McpConfig.remove(file, "a");
        assertThat(Files.readString(file)).isEqualTo("{\n\t\"mcpServers\": {}\n}\n");

        var nested = root.resolve("deep").resolve("nested").resolve("mcp.json");
        McpConfig.add(nested, "docs", stdio("node", null));
        assertThat(Files.readString(nested)).isEqualTo("{\n  \"mcpServers\": {\n    \"docs\": {\n"
                + "      \"command\": \"node\"\n    }\n  }\n}\n");
    }

    @Test
    void rejectsBrokenFilesWhenWriting() throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"mcpServers\":5}");
        assertThatThrownBy(() -> McpConfig.remove(file, "a"))
                .isInstanceOf(McpConfigError.class)
                .hasMessage(file + ": expected an object with an \"mcpServers\" object");

        Files.writeString(file, "{not json");
        assertThatThrownBy(() -> McpConfig.remove(file, "a")).isInstanceOf(McpConfigError.class);
    }

    @Test
    void writesHttpServersWithHeadersAndOAuth() throws Exception {
        var http = new McpServerConfig.Http(null, McpExposure.DIRECT, "Docs search", null, null, null,
                "https://example.com/mcp",
                Map.of("Authorization", "Bearer ${DOCS_TOKEN}"),
                new McpServerConfig.OAuth("pi", null, null, null, null, null, null, null), null);
        McpConfig.add(file, "docs", http);

        assertThat(tree(file)).isEqualTo(json("{\"mcpServers\":{\"docs\":{\"exposure\":\"direct\","
                + "\"description\":\"Docs search\",\"url\":\"https://example.com/mcp\","
                + "\"headers\":{\"Authorization\":\"Bearer ${DOCS_TOKEN}\"},"
                + "\"oauth\":{\"clientId\":\"pi\"}}}}"));
    }
}
