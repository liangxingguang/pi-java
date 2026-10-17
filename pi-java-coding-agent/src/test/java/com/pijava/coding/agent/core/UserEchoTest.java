package com.pijava.coding.agent.core;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.session.InMemorySessionRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * web 回显 bug 回归：跨轮 run 追加语义下，即时回显必须是本轮 prompt
 * （最后一条 user），而不是 transcript 第一条 user。
 *
 * <p>批 2 迁移：回显改走真 {@code message_end(user)} 帧（引擎
 * {@code PiLoop.run} 对 prompt 发 {@code MessageStart/MessageEnd}，会话面漏斗透传）
 * —— 原 {@code UserMessageReceived} 合成事件已删除。</p>
 */
class UserEchoTest {

    private static AgentSession session(String provider, List<List<StreamEvent>> seqs,
                                        java.nio.file.Path tmp) {
        var args = ArgsParser.parse(new String[] {
            "--provider", provider, "--model", "hello", "--no-session"});
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.sequence(provider, seqs));
        return AgentSession.create(args, InMemorySessionRepository.create(),
            providers, new ToolContext(tmp.toString(), Map.of(),
                new DefaultShellExecutor(), new DefaultFileSystem()));
    }

    private static List<StreamEvent> textSeq(String text) {
        var done = AssistantMessage.empty().withContent(List.of(
            new ContentBlock.TextContent(text))).withStopReason(StopReason.STOP);
        return List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.TextStart(0, AssistantMessage.empty()),
            new StreamEvent.TextDelta(0, text, done),
            new StreamEvent.TextEnd(0, text, done),
            new StreamEvent.StreamDone(StopReason.STOP, null, done));
    }

    /** 会话事件流里 message_end(user) 帧携带的文本（即时回显的真源）。 */
    private static List<String> echoedTexts(List<AgentSessionEvent> events) {
        return events.stream()
            .filter(AgentSessionEvent.MessageEnd.class::isInstance)
            .map(e -> ((AgentSessionEvent.MessageEnd) e).message())
            .filter(m -> m instanceof Message.UserMessage)
            .flatMap(m -> m.content().stream())
            .filter(ContentBlock.TextContent.class::isInstance)
            .map(b -> ((ContentBlock.TextContent) b).text())
            .toList();
    }

    @Test
    void secondRunEchoesItsOwnPrompt(@TempDir java.nio.file.Path tmp) throws Exception {
        var session = session("faux-echo", List.of(
            textSeq("one"), textSeq("two")), tmp);
        try (session) {
            var events = new java.util.concurrent.CopyOnWriteArrayList<AgentSessionEvent>();
            try (var ignored = session.subscribe(events::add)) {
                session.processPrompt("first prompt", PromptConfig.defaults()).status();
                session.processPrompt("second prompt", PromptConfig.defaults()).status();
            }
            // 第 1 轮回显 first；第 2 轮回显 second —— 不能重复回显 first
            assertThat(echoedTexts(events)).containsExactly("first prompt", "second prompt");
        }
    }

    @Test
    void echoCarriesUserMessageRole(@TempDir java.nio.file.Path tmp) throws Exception {
        var session = session("faux-echo-role", List.of(textSeq("ok")), tmp);
        try (session) {
            var events = new java.util.concurrent.CopyOnWriteArrayList<AgentSessionEvent>();
            try (var ignored = session.subscribe(events::add)) {
                session.processPrompt("hi", PromptConfig.defaults()).status();
            }
            assertThat(events)
                .filteredOn(e -> e instanceof AgentSessionEvent.MessageEnd me
                    && me.message() instanceof Message.UserMessage)
                .first()
                .extracting(e -> ((AgentSessionEvent.MessageEnd) e).message().role())
                .isEqualTo("user");
        }
    }
}
