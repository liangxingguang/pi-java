package com.pijava.coding.agent.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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
import com.pijava.coding.agent.core.session.InMemorySessionRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L5 W7（pi {@code getLastAssistantText}，agent-session.ts:4526-4545）：/copy 与
 * RPC {@code get_last_assistant_text} 的取值——非正常结束且无有效文本的回合跳过
 * （#12119）；正常结束的空回复仍是最后一条；结果 trim。
 */
class LastAssistantTextTest {

    private static List<StreamEvent> normalText(String text) {
        var done = AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.TextContent(text)))
            .withStopReason(StopReason.STOP);
        return List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.TextEnd(0, text, done),
            new StreamEvent.StreamDone(StopReason.STOP, null, done));
    }

    private static List<StreamEvent> failedWhitespaceText(String text) {
        var done = AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.TextContent(text)))
            .withStopReason(StopReason.ERROR);
        return List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.TextEnd(0, text, done),
            new StreamEvent.StreamDone(StopReason.ERROR, null, done));
    }

    private static AgentSession session(Path tmp, List<List<StreamEvent>> replies) {
        var args = ArgsParser.parse(new String[] {
            "--provider", "faux-copy", "--model", "hello", "--no-session"});
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.sequence("faux-copy", replies));
        var session = AgentSession.create(args, InMemorySessionRepository.create(), providers,
            new ToolContext(tmp.toString(), Map.of(),
                new DefaultShellExecutor(), new DefaultFileSystem()),
            SettingsManager.withStorage(new InMemorySettingsStorage()));
        // 失败回合不得触发自动重试（本用例只测取值）。
        session.setAutoRetryEnabled(false);
        return session;
    }

    @Test
    void skipsFailedTurnWithOnlyWhitespaceText(@TempDir Path tmp) throws Exception {
        try (var s = session(tmp, List.of(normalText("first"), failedWhitespaceText("   ")))) {
            s.processPrompt("one").statusFuture().get(20, TimeUnit.SECONDS);
            s.processPrompt("two").statusFuture().get(20, TimeUnit.SECONDS);
            assertThat(s.lastAssistantText()).isEqualTo("first");
        }
    }

    @Test
    void trimsTheText(@TempDir Path tmp) throws Exception {
        try (var s = session(tmp, List.of(normalText("  hi  ")))) {
            s.processPrompt("one").statusFuture().get(20, TimeUnit.SECONDS);
            assertThat(s.lastAssistantText()).isEqualTo("hi");
        }
    }

    @Test
    void aNormallyEndedEmptyReplyStaysTheLastReply(@TempDir Path tmp) throws Exception {
        try (var s = session(tmp, List.of(normalText("first"), normalText("")))) {
            s.processPrompt("one").statusFuture().get(20, TimeUnit.SECONDS);
            s.processPrompt("two").statusFuture().get(20, TimeUnit.SECONDS);
            assertThat(s.lastAssistantText()).isNull();
        }
    }
}
