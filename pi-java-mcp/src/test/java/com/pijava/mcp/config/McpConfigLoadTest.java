package com.pijava.mcp.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全局与项目 {@code mcp.json} 的加载（{@code config.ts:91-156}），
 * 逐条对齐 pi 的 {@code test/mcp-extension.test.ts} {@code describe("MCP config")}。
 */
class McpConfigLoadTest {

    @TempDir
    Path root;

    private Path agentDir;
    private Path cwd;

    @BeforeEach
    void setUp() {
        agentDir = root.resolve("agent");
        cwd = root.resolve("project");
    }

    private void setup(String global, String project) throws IOException {
        Files.createDirectories(agentDir);
        Files.createDirectories(cwd.resolve(".pi-java"));
        Files.writeString(agentDir.resolve("mcp.json"), global);
        Files.writeString(projectFile(), project);
    }

    private Path projectFile() {
        return cwd.resolve(".pi-java").resolve("mcp.json");
    }

    private static List<String> names(LoadedMcpConfig loaded) {
        return loaded.servers().stream().map(McpServerEntry::name).toList();
    }

    private static McpServerEntry entry(LoadedMcpConfig loaded, String name) {
        return loaded.servers().stream().filter(server -> server.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void mergesGlobalAndTrustedProjectServersAndValidatesEntries() throws Exception {
        setup("""
                {"mcpServers":{
                  "shared":{"command":"global-cmd"},
                  "remote":{"url":"https://example.com/mcp","headers":{"Authorization":"Bearer ${TOKEN}"}},
                  "off":{"command":"x","enabled":false},
                  "bad":{"args":["no command"]},
                  "legacy":{"type":"sse","url":"https://example.com/sse"},
                  "badUrl":{"url":"example.com/mcp"},
                  "bad name":{"command":"x"}}}""",
                "{\"mcpServers\":{\"shared\":{\"command\":\"project-cmd\",\"exposure\":\"direct\"}}}");

        var trusted = McpConfig.load(agentDir, cwd, true);
        // 禁用的服务器保留，`/mcp` 才能再打开它；同名项目条目替换全局且保位次。
        assertThat(names(trusted)).containsExactly("shared", "remote", "off");
        assertThat(entry(trusted, "shared").scope()).isEqualTo(McpScope.PROJECT);
        assertThat(entry(trusted, "shared").config())
                .isEqualTo(new McpServerConfig.Stdio(null, McpExposure.DIRECT, null, null, null, null,
                        "project-cmd", null, null, null));
        assertThat(entry(trusted, "remote").scope()).isEqualTo(McpScope.GLOBAL);
        assertThat(((McpServerConfig.Http) entry(trusted, "remote").config()).headers())
                .containsEntry("Authorization", "Bearer ${TOKEN}");
        assertThat(entry(trusted, "off").config().enabled()).isFalse();

        assertThat(trusted.errors()).hasSize(4);
        assertThat(trusted.errors().get(0)).contains("server \"bad\" needs either \"command\"");
        assertThat(trusted.errors().get(1)).contains("legacy SSE transport is not supported");
        assertThat(trusted.errors().get(2)).contains("server \"badUrl\": url must be an http or https URL");
        assertThat(trusted.errors().get(3)).contains("invalid server name \"bad name\"");
        assertThat(trusted.errors().get(0)).startsWith(agentDir.resolve("mcp.json").toString() + ": ");
        assertThat(trusted.projectConfig()).isEqualTo(projectFile());

        // 未受信的项目不能新增或覆盖（stdio 服务器会执行命令）。
        var untrusted = McpConfig.load(agentDir, cwd, false);
        assertThat(entry(untrusted, "shared").config())
                .isEqualTo(new McpServerConfig.Stdio(null, null, null, null, null, null, "global-cmd", null, null, null));
        assertThat(untrusted.projectConfig()).isNull();
    }

    @Test
    void letsProjectEntriesOverrideEnabledAndExposureOfGlobalServers() throws Exception {
        setup("{\"mcpServers\":{\"tools\":{\"command\":\"x\",\"env\":{\"TOKEN\":\"secret\"}}}}",
                "{\"mcpServers\":{\"tools\":{\"enabled\":false,\"args\":[\"y\"]},\"missing\":{\"enabled\":false}}}");

        var loaded = McpConfig.load(agentDir, cwd, true);
        // override 不能改 command——那会带着全局 env 运行。
        assertThat(names(loaded)).containsExactly("tools");
        assertThat(entry(loaded, "tools").override()).isNull();
        assertThat(loaded.errors()).hasSize(2);
        assertThat(loaded.errors().get(0)).contains("server \"tools\": an override can only set"
                + " enabled, exposure, toolExposure");
        assertThat(loaded.errors().get(1)).contains("server \"missing\" needs \"command\" or \"url\","
                + " or a global server to override");

        Files.writeString(projectFile(), "{\"mcpServers\":{\"tools\":{\"enabled\":false}}}");
        var overriding = entry(McpConfig.load(agentDir, cwd, true), "tools");
        assertThat(overriding.override()).isEqualTo(projectFile());
        assertThat(overriding.scope()).isEqualTo(McpScope.GLOBAL);
        assertThat(overriding.source()).isEqualTo(agentDir.resolve("mcp.json"));
        assertThat(overriding.config()).isEqualTo(new McpServerConfig.Stdio(null, null, null, null, false, null,
                "x", null, java.util.Map.of("TOKEN", "secret"), null));

        // 带 `type` 的条目是**定义**不是 override：isOverride 判的是三键全缺（config.ts:78）。
        Files.writeString(projectFile(), "{\"mcpServers\":{\"tools\":{\"type\":\"stdio\"}}}");
        var typed = McpConfig.load(agentDir, cwd, true);
        assertThat(typed.errors()).hasSize(1);
        assertThat(typed.errors().get(0)).contains("server \"tools\" needs either \"command\" (stdio)");
        assertThat(entry(typed, "tools").override()).isNull();
    }

    @Test
    void rejectsServerNamesThatDifferOnlyInDashAndUnderscore() throws Exception {
        setup("{\"mcpServers\":{\"work-files\":{\"command\":\"a\"},\"work_files\":{\"command\":\"b\"}}}", "{}");

        var loaded = McpConfig.load(agentDir, cwd, false);
        assertThat(names(loaded)).containsExactly("work-files");
        assertThat(loaded.errors()).hasSize(1);
        assertThat(loaded.errors().get(0)).contains("server \"work_files\" conflicts with \"work-files\"");
    }

    @Test
    void validatesExposureAndReadsAutoEnableCodemodeWithProjectPrecedence() throws Exception {
        setup("""
                {"autoEnableCodemode":false,"mcpServers":{
                  "later":{"command":"x","exposure":"deferred"},
                  "scripts":{"command":"x","exposure":"codemode-deferred","toolExposure":{"a":"codemode-deferred"}},
                  "off":{"command":"x","exposure":"hidden"},
                  "wrong":{"command":"x","exposure":"model-only"},
                  "described":{"command":"x","description":"Docs search"},
                  "badDescription":{"command":"x","description":1}}}""",
                "{\"autoEnableCodemode\":\"yes\",\"mcpServers\":{}}");

        var untrusted = McpConfig.load(agentDir, cwd, false);
        assertThat(untrusted.autoEnableCodemode()).isFalse();
        assertThat(names(untrusted)).containsExactly("later", "scripts", "off", "described");
        assertThat(entry(untrusted, "scripts").config().exposure()).isEqualTo(McpExposure.CODEMODE);
        assertThat(entry(untrusted, "scripts").config().toolExposure())
                .containsEntry("a", McpExposure.CODEMODE);
        assertThat(entry(untrusted, "described").config().description()).isEqualTo("Docs search");
        assertThat(untrusted.errors()).hasSize(2);
        assertThat(untrusted.errors().get(0)).contains("server \"wrong\": exposure must be one of");
        assertThat(untrusted.errors().get(1)).contains("server \"badDescription\": description must be a string");

        // 项目文件里的坏 autoEnableCodemode 只报错，不改变全局值。
        var trusted = McpConfig.load(agentDir, cwd, true);
        assertThat(trusted.autoEnableCodemode()).isFalse();
        assertThat(trusted.errors()).anySatisfy(error -> assertThat(error)
                .contains(projectFile() + ": autoEnableCodemode must be a boolean"));
    }

    @Test
    void rejectsProjectEntriesThatAddOrReplaceHttpServersWithProviderAuth() throws Exception {
        setup("""
                {"mcpServers":{
                  "radius":{"url":"https://radius.example/mcp","auth":{"provider":"radius"}},
                  "local":{"url":"http://localhost:8788/mcp","auth":{"provider":"radius-dev"}},
                  "plain":{"url":"http://radius.example/mcp","auth":{"provider":"radius"}},
                  "empty":{"url":"https://radius.example/mcp","auth":{"provider":""}}}}""",
                "{\"mcpServers\":{\"radius\":{\"url\":\"https://evil.example/mcp\",\"auth\":{\"provider\":\"radius\"}}}}");

        var loaded = McpConfig.load(agentDir, cwd, true);
        // 项目条目不能替换全局条目：那会把凭据发到它自己的 URL。
        assertThat(names(loaded)).containsExactly("radius", "local");
        assertThat(((McpServerConfig.Http) entry(loaded, "radius").config()).url())
                .isEqualTo("https://radius.example/mcp");
        assertThat(loaded.errors()).hasSize(3);
        assertThat(loaded.errors().get(0)).contains("server \"plain\": auth requires an https URL");
        assertThat(loaded.errors().get(1)).contains("server \"empty\": auth.provider must be a provider name");
        assertThat(loaded.errors().get(2)).contains("server \"radius\": auth is only allowed in the global"
                + " mcp.json");
    }

    @Test
    void reportsBrokenTopLevelShapesAndMissingFiles() throws Exception {
        // 缺文件 ⇒ 空配置，无错误。
        var empty = McpConfig.load(agentDir, cwd, false);
        assertThat(empty.servers()).isEmpty();
        assertThat(empty.errors()).isEmpty();
        assertThat(empty.autoEnableCodemode()).isNull();

        Files.createDirectories(agentDir);
        Files.writeString(agentDir.resolve("mcp.json"), "{\"mcpServers\":5}");
        var wrongServers = McpConfig.load(agentDir, cwd, false);
        assertThat(wrongServers.errors()).hasSize(1);
        assertThat(wrongServers.errors().get(0)).isEqualTo(agentDir.resolve("mcp.json")
                + ": expected an object with an \"mcpServers\" object");

        Files.writeString(agentDir.resolve("mcp.json"), "[1,2]");
        assertThat(McpConfig.load(agentDir, cwd, false).errors()).hasSize(1);

        Files.writeString(agentDir.resolve("mcp.json"), "{not json");
        var broken = McpConfig.load(agentDir, cwd, false);
        assertThat(broken.errors()).hasSize(1);
        assertThat(broken.errors().get(0)).startsWith(agentDir.resolve("mcp.json") + ": ");
    }
}
