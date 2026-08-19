package com.pijava.coding.agent.core;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.session.InMemorySessionRepository;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6-5a: 会话级事件订阅 —— 多监听器各自收到事件、退订只摘除自己、
 * 监听器抛异常不影响其他监听器。
 */
class AgentSessionSubscribeTest {

    @Test
    void multipleListenersEachReceiveEvents() throws Exception {
        var tmp = Files.createTempDirectory("pi-java-subscribe-test");
        var args = ArgsParser.parse(new String[] {
            "--provider", "faux-sub", "--model", "hello", "--no-session"});
        var providers = ProviderRegistry.create();
        var done = AssistantMessage.empty().withContent(List.of(
            new ContentBlock.TextContent("hi"))).withStopReason("stop");
        providers.register(FauxProvider.sequence("faux-sub", List.of(List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.TextStart(0, AssistantMessage.empty()),
            new StreamEvent.TextDelta(0, "hi", done),
            new StreamEvent.TextEnd(0, "hi", done),
            new StreamEvent.StreamDone("stop", null, done)))));

        var session = AgentSession.create(
            args, InMemorySessionRepository.create(), providers,
            new ToolContext(tmp.toString(), Map.of(),
                new DefaultShellExecutor(), new DefaultFileSystem()));
        try (session) {
            var a = new CopyOnWriteArrayList<AgentSessionEvent>();
            var b = new CopyOnWriteArrayList<AgentSessionEvent>();
            try (var ra = session.subscribe(a::add);
                 var rb = session.subscribe(b::add)) {
                var result = session.processPrompt("hi", PromptConfig.defaults());
                result.status();
            }
            assertThat(a).hasSizeGreaterThan(0);
            assertThat(b).hasSizeGreaterThan(0);
            // 两种监听器收到一致事件流
            assertThat(types(a)).isEqualTo(types(b));
            // P6-5a 首批 4 种事件全部发射
            assertThat(types(a)).contains("MessageUpdate", "EntryAppended",
                "AgentEnd", "AgentSettled");
        }
    }

    @Test
    void unsubscribeRemovesOnlyThatListener() throws Exception {
        var tmp = Files.createTempDirectory("pi-java-subscribe-test");
        var args = ArgsParser.parse(new String[] {
            "--provider", "faux-unsub", "--model", "hello", "--no-session"});
        var providers = ProviderRegistry.create();
        var done = AssistantMessage.empty().withContent(List.of(
            new ContentBlock.TextContent("hi"))).withStopReason("stop");
        providers.register(FauxProvider.sequence("faux-unsub", List.of(List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.TextStart(0, AssistantMessage.empty()),
            new StreamEvent.TextDelta(0, "hi", done),
            new StreamEvent.TextEnd(0, "hi", done),
            new StreamEvent.StreamDone("stop", null, done)))));

        var session = AgentSession.create(
            args, InMemorySessionRepository.create(), providers,
            new ToolContext(tmp.toString(), Map.of(),
                new DefaultShellExecutor(), new DefaultFileSystem()));
        try (session) {
            var kept = new CopyOnWriteArrayList<AgentSessionEvent>();
            var removed = new CopyOnWriteArrayList<AgentSessionEvent>();
            var keepHandle = session.subscribe(kept::add);
            var removeHandle = session.subscribe(removed::add);
            removeHandle.close();
            keepHandle.close();
            var result = session.processPrompt("hi", PromptConfig.defaults());
            result.status();
            // 两个都已退订 → 均不收到
            assertThat(kept).isEmpty();
            assertThat(removed).isEmpty();
        }
    }

    @Test
    void throwingListenerDoesNotAffectOthers() throws Exception {
        var tmp = Files.createTempDirectory("pi-java-subscribe-test");
        var args = ArgsParser.parse(new String[] {
            "--provider", "faux-throw", "--model", "hello", "--no-session"});
        var providers = ProviderRegistry.create();
        var done = AssistantMessage.empty().withContent(List.of(
            new ContentBlock.TextContent("hi"))).withStopReason("stop");
        providers.register(FauxProvider.sequence("faux-throw", List.of(List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.TextStart(0, AssistantMessage.empty()),
            new StreamEvent.TextDelta(0, "hi", done),
            new StreamEvent.TextEnd(0, "hi", done),
            new StreamEvent.StreamDone("stop", null, done)))));

        var session = AgentSession.create(
            args, InMemorySessionRepository.create(), providers,
            new ToolContext(tmp.toString(), Map.of(),
                new DefaultShellExecutor(), new DefaultFileSystem()));
        try (session) {
            var healthy = new CopyOnWriteArrayList<AgentSessionEvent>();
            session.subscribe(e -> {
                throw new IllegalStateException("listener boom");
            });
            session.subscribe(healthy::add);
            var result = session.processPrompt("hi", PromptConfig.defaults());
            result.status();
            assertThat(healthy).hasSizeGreaterThan(0);
        }
    }

    private static List<String> types(List<AgentSessionEvent> events) {
        return events.stream().map(e -> e.getClass().getSimpleName()).toList();
    }
}
