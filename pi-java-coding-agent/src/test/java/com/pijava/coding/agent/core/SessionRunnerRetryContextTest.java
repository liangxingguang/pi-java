package com.pijava.coding.agent.core;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * pi 对齐（agent-session.ts _prepareRetry）：重试前移除尾部 error assistant、
 * 保留既有上下文，用 continue 续跑——不重新注入 user prompt。
 */
class SessionRunnerRetryContextTest {

    private static List<StreamEvent> errorSeq() {
        var partial = AssistantMessage.empty().withStopReason("error");
        return List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.StreamError("error", new RuntimeException("boom"), partial));
    }

    private static List<StreamEvent> textSeq(String text) {
        var done = AssistantMessage.empty().withContent(List.of(
            new ContentBlock.TextContent(text))).withStopReason("stop");
        return List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.TextStart(0, AssistantMessage.empty()),
            new StreamEvent.TextDelta(0, text, done),
            new StreamEvent.TextEnd(0, text, done),
            new StreamEvent.StreamDone("stop", null, done));
    }

    private static List<String> userTexts(AgentSession session) {
        return session.harness().snapshot(session.laneName()).transcript().stream()
            .filter(Entry.Message.class::isInstance)
            .map(e -> (Entry.Message) e)
            .filter(m -> "user".equals(m.message().role()))
            .flatMap(m -> m.message().content().stream())
            .filter(ContentBlock.TextContent.class::isInstance)
            .map(b -> ((ContentBlock.TextContent) b).text())
            .toList();
    }

    @Test
    void retryDoesNotDuplicateUserPrompt(@TempDir java.nio.file.Path tmp) throws Exception {
        var args = ArgsParser.parse(new String[] {
            "--provider", "faux-retry", "--model", "hello", "--no-session"});
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.sequence("faux-retry",
            List.of(errorSeq(), textSeq("recovered"))));
        var session = AgentSession.create(args, providers,
            new ToolContext(tmp.toString(), Map.of(),
                new DefaultShellExecutor(), new DefaultFileSystem()));
        session.setAutoRetryEnabled(true);

        var status = session.processPrompt("go")
            .statusFuture().get(10, TimeUnit.SECONDS);

        assertThat(status.exitCode()).isEqualTo(0);
        assertThat(status.reason()).isEqualTo("stop");
        // user prompt appears exactly once despite the retry
        assertThat(userTexts(session)).containsExactly("go");
    }
}
