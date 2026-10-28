package com.pijava.coding.agent.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.cli.ArgsParser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 批 27（docs/38 T8 裁决⑥）：{@code ensureDiscoveryActive} 的 deferred 臂
 * 全链——配置含 deferred 服务器的会话在 session_start 时自动激活
 * {@code tool_search}（pi mcp/index.ts:494-533；agent-session-mcp.test.ts:942
 * "activates tool_search for deferred MCP tools" 的 java 镜像）。
 *
 * <p>全装配（{@code AgentSession.create}＋文件 settings 存储＋tmp agentDir 的
 * mcp.json）；连接失败无碍——激活从<b>配置</b>判定（pi :501-502 注释逐字），
 * 不等连接。</p>
 */
class ToolSearchMcpActivationTest {

    @TempDir Path tmp;

    private AgentSession rig(String tag, String mcpJson) throws Exception {
        Path agentDir = Files.createDirectories(tmp.resolve(tag + "-agent"));
        Path cwd = Files.createDirectories(tmp.resolve(tag + "-work"));
        Path sessions = Files.createDirectories(tmp.resolve(tag + "-sessions"));
        Files.writeString(agentDir.resolve("mcp.json"), mcpJson);
        var args = ArgsParser.parse(new String[] {
            "--session-dir", sessions.toString(), "--provider", "faux-mcp", "--model", "m"});
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.sequence("faux-mcp", List.of(reply())));
        return AgentSession.create(args, PersistentSessionRepositories.jsonl(sessions),
            providers,
            new ToolContext(cwd.toString(), Map.of(),
                new DefaultShellExecutor(), new DefaultFileSystem()),
            SettingsManager.load(agentDir, cwd, false));
    }

    private static List<StreamEvent> reply() {
        var done = AssistantMessage.empty().withContent(List.of(
            new ContentBlock.TextContent("r"))).withStopReason(StopReason.STOP);
        return List.of(new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.StreamDone(StopReason.STOP, null, done));
    }

    @Test
    void deferredServerActivatesToolSearch() throws Exception {
        try (var session = rig("def", "{\"mcpServers\":{\"docs\":{\"command\":\"run-x\","
                + "\"exposure\":\"deferred\"}}}")) {
            assertThat(session.getActiveToolNames()).contains("tool_search");
            // deferred 工具注册不激活（连接失败的源服不进激活集）
            assertThat(session.getActiveToolNames()).doesNotContain("mcp__docs__query");
        }
    }

    @Test
    void codemodeOnlyServerDoesNotActivateToolSearch() throws Exception {
        // codemode 臂维持 R5 面外替身（java 无 codemode 工具）⇒ tool_search 不激活
        try (var session = rig("cm", "{\"mcpServers\":{\"docs\":{\"command\":\"run-x\"}}}")) {
            assertThat(session.getActiveToolNames()).doesNotContain("tool_search");
        }
    }

    @Test
    void directOnlyServersDoNotActivateToolSearch() throws Exception {
        try (var session = rig("dir", "{\"mcpServers\":{\"docs\":{\"command\":\"run-x\","
                + "\"exposure\":\"direct\"}}}")) {
            assertThat(session.getActiveToolNames()).doesNotContain("tool_search");
        }
    }
}
