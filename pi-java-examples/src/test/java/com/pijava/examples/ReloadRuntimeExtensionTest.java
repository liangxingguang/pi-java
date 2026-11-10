package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.message.Message;
import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ToolContext;
import com.pijava.ai.message.ContentBlock;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.PromptConfig;
import com.pijava.coding.agent.core.slash.SlashContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * docs/51 §4：reload-runtime 验收——工具结果 {@code terminate=true}、命令触发
 * {@code ctx.reload()}、{@code agent_settled} 以 {@code expandPromptTemplates}
 * 派发同名命令。
 */
class ReloadRuntimeExtensionTest {

    @TempDir Path tmp;

    @Test
    void toolRequestsReloadAndTerminates() throws Exception {
        try (var scope = ExampleFixtures.scopeExtensions(tmp, ReloadRuntimeExtension.class);
                var session = ExampleFixtures.newSession(tmp, ExampleFixtures.toolThenDone(
                    "faux-rr", "call-1", ReloadRuntimeExtension.TOOL_NAME, Map.of()))) {
            var result = session.processPrompt("reload", PromptConfig.defaults());
            assertThat(result.status().exitCode()).isZero();

            var toolTexts = toolResultTexts(session);
            assertThat(toolTexts).anySatisfy(text -> assertThat(text)
                .isEqualTo(ReloadRuntimeExtension.TOOL_TEXT));

            // terminate=true 结束本轮：只留下工具调用那条 assistant 消息，收尾 "done" 回合未发生
            var assistantTexts = assistantTexts(session);
            assertThat(assistantTexts).hasSize(1);
            assertThat(assistantTexts.get(0)).isEmpty();
        }
    }

    @Test
    void toolReportsTerminateFlag() throws Exception {
        try (var scope = ExampleFixtures.scopeExtensions(tmp, ReloadRuntimeExtension.class);
                var session = ExampleFixtures.newSession(tmp,
                    ExampleFixtures.textProvider("faux-rr2", "ok"))) {
            var tool = session.services().tools().get(ReloadRuntimeExtension.TOOL_NAME);
            assertThat(tool).isNotNull();
            var toolResult = executeEmptyTool(tool, tmp);
            assertThat(toolResult.text()).isEqualTo(ReloadRuntimeExtension.TOOL_TEXT);
            assertThat(toolResult.terminate()).isTrue();
            assertThat(toolResult.details()).isEmpty();
        }
    }

    @Test
    void commandTriggersReload() throws Exception {
        try (var scope = ExampleFixtures.scopeExtensions(tmp, ReloadRuntimeExtension.class);
                var session = ExampleFixtures.newSession(tmp,
                    ExampleFixtures.textProvider("faux-rr3", "ok"))) {
            var reloads = new AtomicInteger();
            session.addReloadListener(reloads::incrementAndGet);

            var command = session.services().slashCommands().get("reload-runtime");
            assertThat(command).isNotNull();
            command.execute("", SlashContext.of(session)).toCompletableFuture().join();
            assertThat(reloads.get()).isEqualTo(1);
        }
    }

    @Test
    void settledHandlerDispatchesCommandAfterToolRequest() throws Exception {
        try (var scope = ExampleFixtures.scopeExtensions(tmp, ReloadRuntimeExtension.class);
                var session = ExampleFixtures.newSession(tmp, ExampleFixtures.toolThenDone(
                    "faux-rr4", "call-2", ReloadRuntimeExtension.TOOL_NAME, Map.of()))) {
            var reloads = new AtomicInteger();
            session.addReloadListener(reloads::incrementAndGet);

            session.processPrompt("reload", PromptConfig.defaults());

            // agent_settled 的延迟动作在 run 收尾窗内派发 /reload-runtime
            for (int i = 0; i < 40 && reloads.get() == 0; i++) {
                Thread.sleep(50);
            }
            assertThat(reloads.get()).isEqualTo(1);
        }
    }

    // ── 样例夹具 ────────────────────────────────────────────────

    private record ToolOutcome(String text, boolean terminate,
                               Map<String, Object> details) {}

    private static ToolOutcome executeEmptyTool(AgentTool<?, ?> tool, Path cwd) throws Exception {
        var context = new ToolContext(cwd.toString(), Map.of(),
            new com.pijava.agent.tool.DefaultShellExecutor(),
            new com.pijava.agent.tool.DefaultFileSystem());
        return runTool(tool, context);
    }

    @SuppressWarnings("unchecked")
    private static ToolOutcome runTool(AgentTool<?, ?> tool, ToolContext context)
            throws Exception {
        var result = ((AgentTool<Map<String, Object>, Map<String, Object>>) tool)
            .execute("call-x", Map.of(), new com.pijava.ai.AbortSignal(), null, context);
        return new ToolOutcome(ExampleFixtures.textOf(result.content()), result.terminate(),
            result.details());
    }

    private static List<String> toolResultTexts(AgentSession session) {
        return entries(session).stream()
            .filter(Entry.Message.class::isInstance)
            .map(Entry.Message.class::cast)
            .map(Entry.Message::message)
            .filter(Message.ToolResultMessage.class::isInstance)
            .map(Message.ToolResultMessage.class::cast)
            .map(result -> ExampleFixtures.textOf(result.content()))
            .toList();
    }

    private static List<String> assistantTexts(AgentSession session) {
        return entries(session).stream()
            .filter(Entry.Message.class::isInstance)
            .map(Entry.Message.class::cast)
            .map(Entry.Message::message)
            .filter(Message.AssistantMessage.class::isInstance)
            .map(Message.AssistantMessage.class::cast)
            .map(message -> {
                var texts = message.content().stream()
                    .filter(ContentBlock.TextContent.class::isInstance)
                    .map(block -> ((ContentBlock.TextContent) block).text())
                    .toList();
                return String.join("\n", texts);
            })
            .toList();
    }

    private static List<Entry> entries(AgentSession session) {
        return List.copyOf(session.accumulatedEntries());
    }
}
