package com.pijava.coding.agent.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolResult;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.cli.ArgsParser;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 24（docs/35 T8，D4 销号）：{@code _pendingToolNames} 晚注册臂的 java 对位——
 * pi agent-session.ts:447/1524/1529/1810-1814/1829/3590/3681 的逐字五臂＋运行时
 * 注册激活缝（{@code activateRuntimeTools}/{@code deactivateRuntimeTools}）。
 */
class AgentSessionPendingToolsTest {

    @TempDir
    Path tmp;

    private static List<StreamEvent> reply(String text) {
        var done = AssistantMessage.empty().withContent(List.of(
            new ContentBlock.TextContent(text))).withStopReason(StopReason.STOP);
        return List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.TextStart(0, AssistantMessage.empty()),
            new StreamEvent.TextDelta(0, text, done),
            new StreamEvent.TextEnd(0, text, done),
            new StreamEvent.StreamDone(StopReason.STOP, null, done));
    }

    private AgentSession rig(String tag, String... cliArgs) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve(tag));
        var full = new ArrayList<>(List.of(cliArgs));
        full.addAll(List.of("--session-dir", dir.toString(),
            "--provider", "faux-tools", "--model", "hello"));
        var args = ArgsParser.parse(full.toArray(String[]::new));
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.sequence("faux-tools", List.of(reply("r"))));
        return AgentSession.create(args, PersistentSessionRepositories.jsonl(dir), providers,
            new ToolContext(tmp.toString(), Map.of(),
                new DefaultShellExecutor(), new DefaultFileSystem()),
            SettingsManager.withStorage(new InMemorySettingsStorage()));
    }

    /** 最小假工具（注册面用）。 */
    private static AgentTool<Map<String, Object>, Object> fakeTool(String name) {
        return new AgentTool<>() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String label() {
                return name;
            }

            @Override
            public String description() {
                return "fake " + name;
            }

            @Override
            public Map<String, Object> inputSchema() {
                return Map.of("type", "object");
            }

            @Override
            public com.pijava.agent.tool.ExecutionMode executionMode() {
                return new com.pijava.agent.tool.ExecutionMode.Parallel();
            }

            @Override
            public ToolResult<Object> execute(String toolCallId, Map<String, Object> params,
                    com.pijava.ai.AbortSignal signal,
                    com.pijava.agent.tool.ToolUpdateCallback<Object> onUpdate,
                    ToolContext context) {
                return new ToolResult<>(List.of(), null, null, false, List.of());
            }
        };
    }

    @Test
    void deactivationClearsPending() throws Exception {
        try (var session = rig("clear")) {
            session.harness().pendingToolNames().add("mcp__docs__search");
            var active = session.getActiveToolNames();
            // 减员（撤掉一个激活工具）⇒ pending 清（pi :1524）
            session.setActiveToolsByName(active.subList(1, active.size()));
            assertThat(session.harness().pendingToolNames()).isEmpty();
        }
    }

    @Test
    void additiveLoadoutKeepsPending() throws Exception {
        try (var session = rig("keep")) {
            session.harness().pendingToolNames().add("mcp__docs__search");
            var active = new ArrayList<>(session.getActiveToolNames());
            active.add("grep"); // 只增不减（grep 注册但未激活——默认四件外）
            session.setActiveToolsByName(active);
            assertThat(session.harness().pendingToolNames())
                .containsExactly("mcp__docs__search");
        }
    }

    @Test
    void pendingToolActivatesOnRuntimeRegistration() throws Exception {
        try (var session = rig("late")) {
            session.harness().pendingToolNames().add("mcp__docs__search");
            // 晚注册（MCP 服务器后连的对位）⇒ 激活缝消费 pending
            session.services().tools().register(fakeTool("mcp__docs__search"));
            session.activateRuntimeTools(List.of());
            assertThat(session.getActiveToolNames()).contains("mcp__docs__search");
            assertThat(session.harness().pendingToolNames()).isEmpty();
        }
    }

    @Test
    void runtimeActivationRespectsAllowlist() throws Exception {
        // 批 1（B236 裁决③）：allowlist 含 mcp__ 条目 ⇒ MCP 工具被过滤
        try (var session = rig("gate", "--tools", "read,mcp__other__*")) {
            session.services().tools().register(fakeTool("mcp__docs__search"));
            session.activateRuntimeTools(List.of("mcp__docs__search"));
            assertThat(session.getActiveToolNames()).doesNotContain("mcp__docs__search");
        }
    }

    @Test
    void deactivationKeepsPending() throws Exception {
        try (var session = rig("deact")) {
            session.harness().pendingToolNames().add("mcp__docs__search");
            // hidden 重注/disable 撤离臂：清激活但不动 pending
            session.deactivateRuntimeTools(List.of("bash"));
            assertThat(session.getActiveToolNames()).doesNotContain("bash");
            assertThat(session.harness().pendingToolNames())
                .containsExactly("mcp__docs__search");
        }
    }

    @Test
    void runStartDropsPending() throws Exception {
        try (var session = rig("runstart")) {
            session.harness().pendingToolNames().add("mcp__docs__search");
            // pi :1829——run 记装配进转录，未注册的恢复工具放弃等待
            session.processPrompt("hi").status();
            assertThat(session.harness().pendingToolNames()).isEmpty();
        }
    }
}
