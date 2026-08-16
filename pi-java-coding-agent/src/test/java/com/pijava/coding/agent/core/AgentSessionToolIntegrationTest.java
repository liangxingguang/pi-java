package com.pijava.coding.agent.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.harness.SessionSnapshot;
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

    @Test
    void usageEventsUpdateTheSessionTokenCounter() throws Exception {
        var tmp = Files.createTempDirectory("pi-java-usage-test");

        var args = ArgsParser.parse(new String[] {
            "--provider", "faux-usage", "--model", "hello", "--no-session"});
        var providers = ProviderRegistry.create();
        var partial0 = AssistantMessage.empty();
        var usageInfo = new StreamEvent.UsageInfo(123, 45, null);
        var partialWithUsage = AssistantMessage.empty().withUsage(usageInfo);
        providers.register(FauxProvider.sequence("faux-usage", List.of(List.of(
            new StreamEvent.Start(partial0),
            new StreamEvent.TextStart(0, partial0),
            new StreamEvent.TextDelta(0, "hi", partial0),
            new StreamEvent.UsageInfo(123, 45, partialWithUsage),
            new StreamEvent.TextEnd(0, "hi", partialWithUsage),
            new StreamEvent.StreamDone("stop", usageInfo, partialWithUsage)))));
        var toolContext = new ToolContext(
            tmp.toString(), Map.of(),
            new DefaultShellExecutor(), new DefaultFileSystem());

        var session = AgentSession.create(
            args, InMemorySessionRepository.create(),
            providers, toolContext);
        try (session) {
            var lastSnapshot = new AtomicReference<SessionSnapshot>();
            var watch = session.watchSession();
            watch.subscribe(lastSnapshot::set);
            try {
                var result = session.processPrompt(
                    "say hi", PromptConfig.defaults());
                assertThat(result.status().exitCode()).isZero();
            } finally {
                watch.close();
            }

            // The status bar reads SessionSnapshot.totalTokens(); usage from
            // the stream must reach the token counter (regression for the
            // status bar staying at "0 tokens").
            assertThat(lastSnapshot.get()).isNotNull();
            assertThat(lastSnapshot.get().totalTokens()).isEqualTo(168);
        }
    }

    @Test
    void laterRunsDoNotReplayEarlierTranscriptEntries() throws Exception {
        var tmp = Files.createTempDirectory("pi-java-replay-test");

        var args = ArgsParser.parse(new String[] {
            "--provider", "faux-replay", "--model", "hello", "--no-session"});
        var providers = ProviderRegistry.create();
        var helloMsg = AssistantMessage.empty().withContent(List.of(
            new ContentBlock.TextContent("hello"))).withStopReason("stop");
        var worldMsg = AssistantMessage.empty().withContent(List.of(
            new ContentBlock.TextContent("world"))).withStopReason("stop");
        providers.register(FauxProvider.sequence("faux-replay", List.of(
            List.of(
                new StreamEvent.Start(AssistantMessage.empty()),
                new StreamEvent.TextStart(0, AssistantMessage.empty()),
                new StreamEvent.TextDelta(0, "hello", helloMsg),
                new StreamEvent.TextEnd(0, "hello", helloMsg),
                new StreamEvent.StreamDone("stop", null, helloMsg)),
            List.of(
                new StreamEvent.Start(AssistantMessage.empty()),
                new StreamEvent.TextStart(0, AssistantMessage.empty()),
                new StreamEvent.TextDelta(0, "world", worldMsg),
                new StreamEvent.TextEnd(0, "world", worldMsg),
                new StreamEvent.StreamDone("stop", null, worldMsg)))));
        var toolContext = new ToolContext(
            tmp.toString(), Map.of(),
            new DefaultShellExecutor(), new DefaultFileSystem());

        var session = AgentSession.create(
            args, InMemorySessionRepository.create(),
            providers, toolContext);
        try (session) {
            var entries = new CopyOnWriteArrayList<Entry>();
            session.processPrompt("first", PromptConfig.defaults(),
                ignored -> { }, entries::add).status();
            session.processPrompt("second", PromptConfig.defaults(),
                ignored -> { }, entries::add).status();

            // Invariant: each run's end-of-run entry delivery contains each
            // transcript entry exactly once; a second run must not re-deliver
            // the first run's entries (multi-turn TUI duplication guard).
            assertThat(userTexts(entries, "first")).isEqualTo(1);
            assertThat(userTexts(entries, "second")).isEqualTo(1);
            assertThat(assistantTexts(entries, "hello")).isEqualTo(1);
            assertThat(assistantTexts(entries, "world")).isEqualTo(1);
        }
    }

    private static long userTexts(List<Entry> entries, String text) {
        return entries.stream()
            .filter(Entry.Message.class::isInstance)
            .map(Entry.Message.class::cast)
            .filter(m -> "user".equals(m.message().role()))
            .filter(m -> text.equals(joinText(m.message().content())))
            .count();
    }

    private static long assistantTexts(List<Entry> entries, String text) {
        return entries.stream()
            .filter(Entry.Message.class::isInstance)
            .map(Entry.Message.class::cast)
            .filter(m -> "assistant".equals(m.message().role()))
            .filter(m -> text.equals(joinText(m.message().content())))
            .count();
    }

    private static String joinText(List<ContentBlock> blocks) {
        var builder = new StringBuilder();
        for (var block : blocks) {
            if (block instanceof ContentBlock.TextContent text) {
                builder.append(text.text());
            }
        }
        return builder.toString();
    }
}
