package com.pijava.coding.agent.core.slash;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ProviderApi;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.ModelCatalog;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.protocol.AbstractChatApi;
import com.pijava.ai.provider.Provider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.stream.StreamEvent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * B174（{@code docs/23}）：{@code /compact <text>} 把 trailing 文本
 * trim 后作为 customInstructions 透传（pi {@code interactive-mode.ts:3263-3267}）。
 * 用真会话 + 捕获型 provider 钉摘要请求的 prompt 文本。
 */
class CompactSlashCommandTest {

    @TempDir
    Path tmp;

    /** 记录每次请求的用户消息文本；重放固定成功响应。 */
    private static final class CaptureProvider implements Provider {
        final List<String> userTexts = new CopyOnWriteArrayList<>();

        @Override public String name() { return "capture"; }
        @Override public String displayName() { return "Capture"; }
        @Override public Set<Class<? extends ProviderApi>> supportedApis() {
            return Set.of(com.pijava.ai.api.ChatApi.class);
        }
        @Override
        public <T extends ProviderApi> T createApi(Class<T> apiType, ApiOptions options) {
            return apiType.cast(new CaptureApi(this));
        }
        @Override public ModelCatalog builtinModels() { return ModelCatalog.empty(); }
    }

    private static final class CaptureApi extends AbstractChatApi {
        private final CaptureProvider provider;

        CaptureApi(CaptureProvider provider) { this.provider = provider; }

        @Override public String apiName() { return "capture"; }
        @Override protected boolean resolvesRequestOptions() { return false; }

        @Override
        protected void streamInternal(StreamRequest request,
                                      java.util.concurrent.SubmissionPublisher<StreamEvent> publisher) {
            for (Message message : request.messages()) {
                if (message instanceof Message.UserMessage user) {
                    var parts = new java.util.ArrayList<String>();
                    for (ContentBlock block : user.content()) {
                        if (block instanceof ContentBlock.TextContent text) {
                            parts.add(text.text());
                        }
                    }
                    provider.userTexts.add(String.join("\n", parts));
                }
            }
            var msg = AssistantMessage.empty().withContent(List.of(
                new ContentBlock.TextContent("ok"))).withStopReason("stop");
            publisher.submit(new StreamEvent.Start(AssistantMessage.empty()));
            publisher.submit(new StreamEvent.TextDelta(0, "ok", msg.withStopReason(null)));
            publisher.submit(StreamEvent.StreamDone.settle("stop", msg));
        }
    }

    /** 构造带两轮历史的会话（默认 keep=20000：第二轮 user 足够大 ⇒ 切点前有历史可摘要）。 */
    private AgentSession sessionWithTwoTurns(CaptureProvider capture) throws Exception {
        var args = com.pijava.coding.agent.cli.ArgsParser.parse(
            new String[] {"--provider", "capture", "--model", "hello", "--no-session"});
        var providers = ProviderRegistry.create();
        providers.register(capture);
        // 同 RpcDispatcherTest：FileSettingsStorage 在构造时捕获 user.home，
        // 窗口只包住 create 以免碰到真实用户目录。
        var home = Files.createTempDirectory("compact-slash-home");
        String savedHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            var session = AgentSession.create(args, providers,
                new ToolContext(tmp.toString(), Map.of(),
                    new DefaultShellExecutor(), new DefaultFileSystem()));
            session.processPrompt("first").statusFuture().get(10, TimeUnit.SECONDS);
            // ~25k tokens ⇒ 尾部累加在第二轮 user 处达到 keep 门，切点落在它身上，
            // 第一轮 user/assistant 进历史摘要。
            session.processPrompt("x".repeat(100_000)).statusFuture().get(10, TimeUnit.SECONDS);
            return session;
        } finally {
            System.setProperty("user.home", savedHome);
        }
    }

    @Test
    void trailingTextIsTrimmedAndPassedAsCustomInstructions() throws Exception {
        var capture = new CaptureProvider();
        var session = sessionWithTwoTurns(capture);

        var result = CommandRegistry.withBuiltins()
            .dispatch("/compact Focus on tests", SlashContext.of(session))
            .toCompletableFuture().join();

        assertThat(result).isEqualTo("Compacted.");
        assertThat(capture.userTexts)
            .as("pi interactive-mode.ts:3264：trailing 文本进入摘要 prompt")
            .anyMatch(text -> text.endsWith("\n\nAdditional focus: Focus on tests"));
    }

    @Test
    void noTrailingTextPassesNull() throws Exception {
        var capture = new CaptureProvider();
        var session = sessionWithTwoTurns(capture);

        var result = CommandRegistry.withBuiltins()
            .dispatch("/compact", SlashContext.of(session))
            .toCompletableFuture().join();

        assertThat(result).isEqualTo("Compacted.");
        assertThat(capture.userTexts)
            .as("无 trailing ⇒ 不带 Additional focus")
            .noneMatch(text -> text.contains("Additional focus"));
    }

    @Test
    void whitespaceTrailingTextPassesNull() throws Exception {
        var capture = new CaptureProvider();
        var session = sessionWithTwoTurns(capture);

        var result = CommandRegistry.withBuiltins()
            .dispatch("/compact    ", SlashContext.of(session))
            .toCompletableFuture().join();

        assertThat(result).isEqualTo("Compacted.");
        assertThat(capture.userTexts)
            .as("trim 后为空 ⇒ null（pi slice+trim）")
            .noneMatch(text -> text.contains("Additional focus"));
    }
}
