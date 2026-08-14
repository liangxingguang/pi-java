package com.pijava.coding.agent.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.session.InMemorySessionRepository;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end regression for the full submit → stream → tool-execution path:
 * a FauxProvider emits a write tool call, and the harness must execute it
 * against the injected ToolContext (catches a null ToolContext, which broke
 * every tool call).
 */
class AgentSessionToolIntegrationTest {

    @Test
    void promptExecutesToolAgainstInjectedContext() throws Exception {
        var tmp = Files.createTempDirectory("pi-java-tool-test");

        var args = ArgsParser.parse(new String[] {
            "--provider", "faux-tool", "--model", "hello", "--no-session"});
        var providers = ProviderRegistry.create();
        Map<String, Object> argsMap = Map.of(
            "path", "hello.py",
            "content", "print(\"hello\")");
        var toolMsg = AssistantMessage.empty().withContent(List.of(
            new ContentBlock.ToolUseContent("id1", "write", argsMap)))
            .withStopReason("tool_use");
        var doneMsg = AssistantMessage.empty().withContent(List.of(
            new ContentBlock.TextContent("done"))).withStopReason("stop");
        providers.register(FauxProvider.sequence("faux-tool", List.of(
            List.of(
                new StreamEvent.Start(AssistantMessage.empty()),
                new StreamEvent.ToolCallStart(0, AssistantMessage.empty()),
                new StreamEvent.ToolCallEnd(0, "id1", "write", argsMap, toolMsg),
                new StreamEvent.StreamDone("tool_use", null, toolMsg)),
            List.of(
                new StreamEvent.Start(AssistantMessage.empty()),
                new StreamEvent.TextStart(0, AssistantMessage.empty()),
                new StreamEvent.TextDelta(0, "done", doneMsg),
                new StreamEvent.TextEnd(0, "done", doneMsg),
                new StreamEvent.StreamDone("stop", null, doneMsg)))));
        var toolContext = new ToolContext(
            tmp.toString(), Map.of(),
            new DefaultShellExecutor(), new DefaultFileSystem());

        var session = AgentSession.create(
            args, InMemorySessionRepository.create(),
            providers, toolContext);
        try (session) {
            var result = session.processPrompt(
                "write a hello file", PromptConfig.defaults());
            var status = result.status();

            assertThat(status.exitCode()).isZero();
            Path written = tmp.resolve("hello.py");
            assertThat(Files.exists(written)).isTrue();
            assertThat(Files.readString(written))
                .isEqualTo("print(\"hello\")");
        }
    }
}
