package com.pijava.coding.agent.extension.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pijava.mcp.config.McpExposure;
import com.pijava.mcp.config.McpServerConfig;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 24（docs/35 §6）：内置 MCP 扩展的会话行为——session_start 装配与后台连接、
 * 提示段生产、tool_call 就绪等待、首提示有界等待、mcp_servers_change 热插拔、
 * session_shutdown 收敛（map/07 G 组 #73/#76/#77/#79/#80）。
 */
class McpExtensionSessionTest {

    @TempDir
    Path agentDir;

    @TempDir
    Path cwd;

    private void writeGlobalMcpJson(String content) throws Exception {
        Files.writeString(agentDir.resolve("mcp.json"), content);
    }

    private static String directStdio(String name) {
        return "{\"mcpServers\":{\"" + name + "\":{\"command\":\"run-x\","
            + "\"exposure\":\"direct\"}}}";
    }

    @Test
    void directServerConnectsAndRegistersTools() throws Exception {
        var server = ScriptedMcpServer.withTools("search", "fetch");
        writeGlobalMcpJson(directStdio("docs"));
        var rig = McpExtensionTestRig.create(agentDir, cwd, false, server);
        rig.startSession();
        awaitPending(rig);
        assertThat(rig.tools.toolNames())
            .containsExactlyInAnyOrder("mcp__docs__search", "mcp__docs__fetch");
        // 无间接 exposure 服务器 ⇒ 无提示段、无通知
        assertThat(rig.fireBeforeAgentStart()).doesNotContainKey("mcp_servers");
        assertThat(rig.ui.notifications).isEmpty();
        rig.shutdownSession();
        assertThat(server.allClosed()).isTrue();
    }

    @Test
    void codemodeServerStaysInactiveButEntersPromptSection() throws Exception {
        var server = ScriptedMcpServer.withTools("query");
        writeGlobalMcpJson("{\"mcpServers\":{\"docs\":{\"command\":\"run-x\"}}}");
        var rig = McpExtensionTestRig.create(agentDir, cwd, false, server);
        rig.startSession();
        awaitPending(rig);
        // codemode（默认 exposure）⇒ 注册不激活（激活面由会话缝处理）；
        // 提示段列出（渲染纯函数 map/07 #75 对位）
        var sections = rig.fireBeforeAgentStart();
        assertThat(sections.get("mcp_servers"))
            .contains("MCP servers whose tools are not declared to you.")
            .contains("- mcp__docs (codemode)");
        // 裁决⑥：java 无 codemode ⇒ 一次性不可达警告
        assertThat(rig.ui.hasNotification("unreachable", "warning")).isTrue();
        rig.fireBeforeAgentStart();
        assertThat(rig.ui.notifications.stream()
            .filter(n -> n[0].contains("unreachable"))).hasSize(1);
        rig.shutdownSession();
    }

    @Test
    void disabledServerNeverConnects() throws Exception {
        var server = ScriptedMcpServer.withTools("x");
        writeGlobalMcpJson("{\"mcpServers\":{\"off\":{\"command\":\"run-x\","
            + "\"enabled\":false}}}");
        var rig = McpExtensionTestRig.create(agentDir, cwd, false, server);
        rig.startSession();
        awaitPending(rig);
        assertThat(server.transportCount()).isZero();
        rig.shutdownSession();
    }

    @Test
    void configErrorIsReportedOnce() throws Exception {
        writeGlobalMcpJson("{not json");
        var rig = McpExtensionTestRig.create(agentDir, cwd, false, null);
        rig.startSession();
        awaitPending(rig);
        assertThat(rig.ui.hasNotification("MCP servers need attention:", "warning")).isTrue();
        assertThat(rig.ui.notifications.get(0)[0]).contains("config: ");
        rig.shutdownSession();
    }

    @Test
    void firstPromptWaitsForDirectServerWithTimeoutNotice() throws Exception {
        var server = ScriptedMcpServer.withTools("slow");
        writeGlobalMcpJson(directStdio("docs"));
        var rig = McpExtensionTestRig.create(agentDir, cwd, false, server, 50L);
        var gate = server.hold("tools/list");
        rig.startSession();
        // 连接在进行中（tools/list 被扣）⇒ 首提示 50ms 超时放行＋通知（文案逐字）
        rig.fireBeforeAgentStart();
        assertThat(rig.ui.hasNotification(
            "MCP servers are still connecting; their tools become available once connected.",
            "info")).isTrue();
        gate.complete(null);
        awaitPending(rig);
        // 第二提示不再等（waitedForStartup）
        rig.ui.notifications.clear();
        rig.fireBeforeAgentStart();
        assertThat(rig.ui.notifications).isEmpty();
        rig.shutdownSession();
    }

    @Test
    void toolCallWaitsForItsServerToBeReady() throws Exception {
        var server = ScriptedMcpServer.withTools("search");
        writeGlobalMcpJson(directStdio("docs"));
        var rig = McpExtensionTestRig.create(agentDir, cwd, false, server);
        rig.startSession();
        awaitPending(rig);
        // 新一轮 session_start 且连接被扣（tools/list 门）⇒ 工具调用阻塞到就绪
        var gate = server.hold("tools/list");
        rig.shutdownSession();
        rig.startSession();
        var fired = CompletableFuture.runAsync(() -> {
            try {
                rig.fireToolCall("mcp__docs__search");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, Thread::startVirtualThread);
        Thread.sleep(150);
        assertThat(fired).isNotDone();
        gate.complete(null);
        fired.get(5, TimeUnit.SECONDS);
        rig.shutdownSession();
    }

    @Test
    void toolCallIgnoresUnknownTools() throws Exception {
        writeGlobalMcpJson(directStdio("docs"));
        var rig = McpExtensionTestRig.create(agentDir, cwd, false,
            ScriptedMcpServer.withTools("search"));
        rig.startSession();
        awaitPending(rig);
        // 未注册名（getAllTools 查无）⇒ 直接返回不等
        rig.fireToolCall("no_such_tool");
        rig.shutdownSession();
    }

    @Test
    void registeredServerHotPlugsOnChange() throws Exception {
        var server = ScriptedMcpServer.withTools("plug");
        var rig = McpExtensionTestRig.create(agentDir, cwd, false, server);
        rig.startSession();
        awaitPending(rig);
        assertThat(rig.tools.toolNames()).isEmpty();
        // 装配后注册 ⇒ mcp_servers_change ⇒ 立即连接＋注册工具
        rig.base.forExtension("ext-a", "/a.jar").registerMcpServer("plugger",
            new McpServerConfig.Stdio(null, McpExposure.DIRECT,
                null, null, null, null, "run-plug", List.of(), null, null));
        awaitPending(rig);
        assertThat(rig.tools.toolNames()).containsExactly("mcp__plugger__plug");
        rig.shutdownSession();
        assertThat(server.allClosed()).isTrue();
    }

    @Test
    void configuredServerOverridesRegisteredSameName() throws Exception {
        writeGlobalMcpJson(directStdio("docs"));
        var configured = ScriptedMcpServer.withTools("fromConfig");
        var rig = McpExtensionTestRig.create(agentDir, cwd, false, configured);
        rig.startSession();
        // 装配后同名注册 ⇒ 热插拔臂：mcp.json 同名压掉 ⇒ 进 overridden，不连接
        rig.base.forExtension("ext-a", "/a.jar").registerMcpServer("docs",
            new McpServerConfig.Stdio(null, null, null, null, null, null,
                "run-other", List.of(), null, null));
        awaitPending(rig);
        assertThat(rig.tools.toolNames()).containsExactly("mcp__docs__fromConfig");
        rig.shutdownSession();
    }

    private static void awaitPending(McpExtensionTestRig rig) {
        var pending = rig.ext.state().pending;
        if (pending != null) {
            pending.join();
        }
    }
}
