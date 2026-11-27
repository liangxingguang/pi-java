package com.pijava.coding.agent.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolResult;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.codemode.CodemodeDetails;
import com.pijava.coding.agent.codemode.CodemodeStore;
import com.pijava.coding.agent.core.session.InMemorySessionRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * B5 端到端（docs/08 §8.6）：faux provider 发 {@code codemode} 调用，脚本经
 * {@code tools.read()} 调真实 read 工具并写 store，断言最终 tool result、
 * codemode details 与 {@code codemode-store} 自定义条目。
 */
class CodemodeSessionE2eTest {

    @TempDir Path tmp;

    @Test
    void scriptCallsNestedReadAndPersistsStoreEntry() throws Exception {
        Files.writeString(tmp.resolve("note.txt"), "hello from file");
        var args = ArgsParser.parse(new String[] {
            "--provider", "faux-codemode", "--model", "m", "--no-session", "--no-mcp",
            "--tools", "read,codemode"});
        var providers = ProviderRegistry.create();
        var script = "store(\"k\", 1);\n"
            + "store(\"modelCount\", (await models.list()).length);\n"
            + "return await tools.read({path: \"note.txt\"});";
        var toolTurn = AssistantMessage.empty().withContent(List.of(
            new ContentBlock.ToolUseContent("c1", "codemode", Map.of("code", script))))
            .withStopReason(StopReason.TOOL_USE);
        var doneTurn = AssistantMessage.empty().withContent(List.of(
            new ContentBlock.TextContent("done"))).withStopReason(StopReason.STOP);
        providers.register(FauxProvider.sequence("faux-codemode", List.of(
            List.of(
                new StreamEvent.Start(AssistantMessage.empty()),
                new StreamEvent.ToolCallStart(0, AssistantMessage.empty()),
                new StreamEvent.ToolCallEnd(0, "c1", "codemode", Map.of("code", script), toolTurn),
                new StreamEvent.StreamDone(StopReason.TOOL_USE, null, toolTurn)),
            List.of(
                new StreamEvent.Start(AssistantMessage.empty()),
                new StreamEvent.TextStart(0, AssistantMessage.empty()),
                new StreamEvent.TextDelta(0, "done", doneTurn),
                new StreamEvent.TextEnd(0, "done", doneTurn),
                new StreamEvent.StreamDone(StopReason.STOP, null, doneTurn)))));

        try (var session = AgentSession.create(args, InMemorySessionRepository.create(), providers,
                new ToolContext(tmp.toString(), Map.of(),
                    new DefaultShellExecutor(), new DefaultFileSystem()),
                SettingsManager.withStorage(new InMemorySettingsStorage()))) {
            var events = new CopyOnWriteArrayList<AgentSessionEvent>();
            try (var subscription = session.subscribe(events::add)) {
                var result = session.processPrompt("run", PromptConfig.defaults());
                assertThat(result.status().exitCode()).isZero();
            }

            assertThat(session.getActiveToolNames()).contains("codemode", "read");

            var toolResult = session.accumulatedEntries().stream()
                .filter(entry -> entry instanceof Entry.Message message
                    && message.message() instanceof Message.ToolResultMessage tool
                    && "c1".equals(tool.toolUseId()))
                .map(entry -> (Message.ToolResultMessage) ((Entry.Message) entry).message())
                .findFirst().orElseThrow();
            assertThat(toolResult.isError()).isFalse();
            var text = toolResult.content().stream()
                .filter(block -> block instanceof ContentBlock.TextContent)
                .map(block -> ((ContentBlock.TextContent) block).text())
                .reduce("", (left, right) -> left + right);
            assertThat(text).contains("Script completed", "hello from file");

            var details = events.stream()
                .filter(event -> event instanceof AgentSessionEvent.ToolExecutionEnd end
                    && "c1".equals(end.toolCallId()))
                .map(event -> ((AgentSessionEvent.ToolExecutionEnd) event).result())
                .filter(ToolResult.class::isInstance)
                .map(result -> ((ToolResult<?>) result).details())
                .filter(CodemodeDetails.class::isInstance)
                .map(CodemodeDetails.class::cast)
                .findFirst().orElseThrow();
            assertThat(details.calls()).hasSize(1);
            assertThat(details.calls().get(0).name()).isEqualTo("read");
            assertThat(details.calls().get(0).status()).isEqualTo("ok");

            var store = session.accumulatedEntries().stream()
                .filter(entry -> entry instanceof Entry.Custom custom
                    && CodemodeStore.ENTRY_TYPE.equals(custom.customType()))
                .map(entry -> (Entry.Custom) entry)
                .findFirst().orElseThrow();
            assertThat(store.data()).isInstanceOf(Map.class);
            assertThat(((Map<?, ?>) store.data()).get("set"))
                .isEqualTo(Map.of("k", 1L, "modelCount", 0L));
        }
    }
}
