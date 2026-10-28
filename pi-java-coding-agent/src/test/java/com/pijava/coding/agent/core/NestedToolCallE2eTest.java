package com.pijava.coding.agent.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ExecutionMode;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolResult;
import com.pijava.agent.tool.ToolUpdateCallback;
import com.pijava.ai.AbortSignal;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.NestedToolCallRecord;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.session.InMemorySessionRepository;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.PiExtension;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 批 27（B162/T3+T4）：嵌套工具调用的端到端——pi
 * {@code test/suite/agent-session-tool-orchestration.test.ts:14-144} 的 java 镜像。
 *
 * <p>编排扩展注册三工具：{@code echo}（direct）、{@code helper}（codemode——
 * 注册不激活但可经 executeTool 调用）、{@code run_tools}（model-only——激活但
 * 不可被工具调用）。{@code run_tools} 执行中经 {@code ctx.executeTool()} 调
 * helper/echo/自身（自递归 ⇒ "Tool run_tools not found"）。</p>
 */
class NestedToolCallE2eTest {

    @TempDir Path tmp;

    private ClassLoader previousTccl;

    @BeforeEach
    void installScopedLoader() throws Exception {
        var servicesDir = Files.createDirectories(tmp.resolve("META-INF/services"));
        Files.writeString(
            servicesDir.resolve("com.pijava.coding.agent.extension.PiExtension"),
            OrchestratorExtension.class.getName() + "\n");
        Path testClasses = Path.of(getClass().getProtectionDomain()
            .getCodeSource().getLocation().toURI());
        previousTccl = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(new URLClassLoader(
            // classpath 根是 tmp（META-INF/services 的父），不是 services 目录本身
            new java.net.URL[] {tmp.toUri().toURL(), testClasses.toUri().toURL()},
            previousTccl));
    }

    @AfterEach
    void restoreLoader() {
        Thread.currentThread().setContextClassLoader(previousTccl);
    }

    @Test
    void nestedCallsAreRecordedOnTheModelIssuedResult() throws Exception {
        var args = ArgsParser.parse(new String[] {
            "--provider", "faux-nested", "--model", "hello", "--no-session",
            // 防真 home 的 mcp.json 继承（环境性红坑）；tool-search 内置无害
            "--no-mcp"});
        var providers = ProviderRegistry.create();
        var toolTurn = AssistantMessage.empty().withContent(List.of(
            new ContentBlock.ToolUseContent("top", "run_tools", Map.of())))
            .withStopReason(StopReason.TOOL_USE);
        var doneTurn = AssistantMessage.empty().withContent(List.of(
            new ContentBlock.TextContent("done"))).withStopReason(StopReason.STOP);
        providers.register(FauxProvider.sequence("faux-nested", List.of(
            List.of(
                new StreamEvent.Start(AssistantMessage.empty()),
                new StreamEvent.ToolCallStart(0, AssistantMessage.empty()),
                new StreamEvent.ToolCallEnd(0, "top", "run_tools", Map.of(), toolTurn),
                new StreamEvent.StreamDone(StopReason.TOOL_USE, null, toolTurn)),
            List.of(
                new StreamEvent.Start(AssistantMessage.empty()),
                new StreamEvent.TextStart(0, AssistantMessage.empty()),
                new StreamEvent.TextDelta(0, "done", doneTurn),
                new StreamEvent.TextEnd(0, "done", doneTurn),
                new StreamEvent.StreamDone(StopReason.STOP, null, doneTurn)))));

        var session = AgentSession.create(args, InMemorySessionRepository.create(),
            providers, new ToolContext(tmp.toString(), Map.of(),
                new DefaultShellExecutor(), new DefaultFileSystem()),
            SettingsManager.withStorage(new InMemorySettingsStorage()));
        try (session) {
            var events = new CopyOnWriteArrayList<AgentSessionEvent>();
            try (var sub = session.subscribe(events::add)) {
                var result = session.processPrompt("orchestrate", PromptConfig.defaults());
                assertThat(result.status().exitCode()).isZero();
            }

            // 激活面：echo＋run_tools 在（默认四件之外）；helper 不在（codemode 不声明）
            assertThat(session.getActiveToolNames()).contains("echo", "run_tools")
                .doesNotContain("helper");

            // 事件：顶层 run_tools 无父 id；三个嵌套调用带 parentToolCallId="top"
            var starts = events.stream()
                .filter(e -> e instanceof AgentSessionEvent.ToolExecutionStart)
                .map(e -> (AgentSessionEvent.ToolExecutionStart) e).toList();
            assertThat(starts.stream().filter(e -> e.parentToolCallId() == null)
                .map(AgentSessionEvent.ToolExecutionStart::toolName))
                .contains("run_tools");
            assertThat(starts.stream().filter(e -> "top".equals(e.parentToolCallId()))
                .map(AgentSessionEvent.ToolExecutionStart::toolCallId))
                .containsExactly("top/1", "top/2", "top/3");

            // 持久化：run_tools 的 tool result 消息带 nestedCalls
            // （pi :103-112 同断言——挂载先于落盘）
            var persisted = session.accumulatedEntries().stream()
                .filter(e -> e instanceof Entry.Message m
                    && m.message() instanceof Message.ToolResultMessage tr
                    && "top".equals(tr.toolUseId()))
                .map(e -> (Message.ToolResultMessage) ((Entry.Message) e).message())
                .findFirst().orElseThrow();
            assertThat(persisted.nestedCalls()).isNotNull();
            assertThat(persisted.nestedCalls().calls())
                .extracting(NestedToolCallRecord::id)
                .containsExactly("top/1", "top/2", "top/3");
            assertThat(persisted.nestedCalls().calls())
                .extracting(NestedToolCallRecord::status)
                .containsExactly(NestedToolCallRecord.Status.OK,
                    NestedToolCallRecord.Status.OK, NestedToolCallRecord.Status.ERROR);
            // 自递归失败文本逐字（pi :100 同断言）
            assertThat(persisted.nestedCalls().calls().get(2).error())
                .isEqualTo("Tool run_tools not found");
            assertThat(persisted.nestedCalls().complete()).isTrue();
        }
    }

    @Test
    void resultsWithoutNestedCallsStayUnchanged() throws Exception {
        var args = ArgsParser.parse(new String[] {
            "--provider", "faux-plain", "--model", "hello", "--no-session", "--no-mcp"});
        var providers = ProviderRegistry.create();
        var echoTurn = AssistantMessage.empty().withContent(List.of(
            new ContentBlock.ToolUseContent("e1", "echo", Map.of())))
            .withStopReason(StopReason.TOOL_USE);
        var doneTurn = AssistantMessage.empty().withContent(List.of(
            new ContentBlock.TextContent("done"))).withStopReason(StopReason.STOP);
        providers.register(FauxProvider.sequence("faux-plain", List.of(
            List.of(
                new StreamEvent.Start(AssistantMessage.empty()),
                new StreamEvent.ToolCallStart(0, AssistantMessage.empty()),
                new StreamEvent.ToolCallEnd(0, "e1", "echo", Map.of(), echoTurn),
                new StreamEvent.StreamDone(StopReason.TOOL_USE, null, echoTurn)),
            List.of(
                new StreamEvent.Start(AssistantMessage.empty()),
                new StreamEvent.TextStart(0, AssistantMessage.empty()),
                new StreamEvent.TextDelta(0, "done", doneTurn),
                new StreamEvent.TextEnd(0, "done", doneTurn),
                new StreamEvent.StreamDone(StopReason.STOP, null, doneTurn)))));

        var session = AgentSession.create(args, InMemorySessionRepository.create(),
            providers, new ToolContext(tmp.toString(), Map.of(),
                new DefaultShellExecutor(), new DefaultFileSystem()),
            SettingsManager.withStorage(new InMemorySettingsStorage()));
        try (session) {
            var result = session.processPrompt("echo", PromptConfig.defaults());
            assertThat(result.status().exitCode()).isZero();

            // pi :133-144：无嵌套调用的结果消息不含 nestedCalls 键
            var persisted = session.accumulatedEntries().stream()
                .filter(e -> e instanceof Entry.Message m
                    && m.message() instanceof Message.ToolResultMessage)
                .map(e -> (Message.ToolResultMessage) ((Entry.Message) e).message())
                .findFirst().orElseThrow();
            assertThat(persisted.nestedCalls()).isNull();
        }
    }
}
