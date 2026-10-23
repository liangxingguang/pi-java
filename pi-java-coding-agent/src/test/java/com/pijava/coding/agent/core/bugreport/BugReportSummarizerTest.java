package com.pijava.coding.agent.core.bugreport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ProviderApi;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.ModelCatalog;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.protocol.AbstractChatApi;
import com.pijava.ai.provider.Provider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.AgentSession;
import org.junit.jupiter.api.Test;

/**
 * 批 15（T3b）：pi {@code generateBugReportSummary} 对拍——四守卫各臂、prompt
 * 四段拼装、maxTokens 两臂、无模型 {@code No model selected}。
 */
class BugReportSummarizerTest {

    /** 脚本化响应形状。 */
    private enum Kind { TEXT, ERROR, ABORTED, TOOL_CALL, EMPTY }

    private record Script(Kind kind, String text) {}

    /** 按请求序弹脚本的 provider；记录 user 文本与 maxTokens。 */
    private static final class ScriptedProvider implements Provider {
        private final java.util.Queue<Script> scripts = new ConcurrentLinkedQueue<>();
        private final List<String> userTexts = new CopyOnWriteArrayList<>();
        private final List<Integer> maxTokensSeen = new CopyOnWriteArrayList<>();

        ScriptedProvider script(Kind kind, String text) {
            scripts.add(new Script(kind, text));
            return this;
        }

        ProviderRegistry register() {
            var registry = ProviderRegistry.create();
            registry.register(this);
            return registry;
        }

        @Override public String name() { return "scripted"; }
        @Override public String displayName() { return "Scripted"; }
        @Override public Set<Class<? extends ProviderApi>> supportedApis() {
            return Set.of(com.pijava.ai.api.ChatApi.class);
        }

        @Override
        public <T extends ProviderApi> T createApi(Class<T> apiType, ApiOptions options) {
            return apiType.cast(new ScriptedApi(this));
        }

        @Override public ModelCatalog builtinModels() { return ModelCatalog.empty(); }

        private static final class ScriptedApi extends AbstractChatApi {
            private final ScriptedProvider provider;

            ScriptedApi(ScriptedProvider provider) {
                this.provider = provider;
            }

            @Override public String apiName() { return "scripted"; }
            @Override protected boolean resolvesRequestOptions() { return false; }

            @Override
            protected void streamInternal(StreamRequest request,
                    java.util.concurrent.SubmissionPublisher<StreamEvent> publisher) {
                provider.maxTokensSeen.add(request.maxTokens());
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
                var script = provider.scripts.poll();
                if (script == null) {
                    script = new Script(Kind.TEXT, "summary");
                }
                switch (script.kind()) {
                    case TEXT -> {
                        var msg = AssistantMessage.empty()
                            .withContent(List.of(new ContentBlock.TextContent(script.text())))
                            .withStopReason(StopReason.STOP);
                        publisher.submit(new StreamEvent.Start(AssistantMessage.empty()));
                        publisher.submit(new StreamEvent.TextDelta(0, script.text(), msg));
                        publisher.submit(StreamEvent.StreamDone.settle(StopReason.STOP, msg));
                    }
                    case EMPTY -> {
                        var msg = AssistantMessage.empty()
                            .withContent(List.of())
                            .withStopReason(StopReason.STOP);
                        publisher.submit(new StreamEvent.Start(AssistantMessage.empty()));
                        publisher.submit(StreamEvent.StreamDone.settle(StopReason.STOP, msg));
                    }
                    case ERROR -> publisher.submit(StreamEvent.StreamError.settle(
                        StopReason.ERROR, new RuntimeException(script.text()),
                        AssistantMessage.empty()));
                    case ABORTED -> publisher.submit(StreamEvent.StreamError.settle(
                        StopReason.ABORTED, null, AssistantMessage.empty()));
                    case TOOL_CALL -> {
                        // 终局 partial 直接带 ToolUseContent 块（pi 的响应形状）。
                        var msg = AssistantMessage.empty()
                            .withContent(List.of(new ContentBlock.ToolUseContent(
                                "tc1", "bash", Map.of("command", "ls"))))
                            .withStopReason(StopReason.TOOL_USE);
                        publisher.submit(new StreamEvent.Start(AssistantMessage.empty()));
                        publisher.submit(StreamEvent.StreamDone.settle(
                            StopReason.TOOL_USE, msg));
                    }
                }
            }
        }
    }

    private AgentSession session(ScriptedProvider provider, String... extraArgs) {
        var argv = new java.util.ArrayList<>(List.of(
            "--provider", "scripted", "--model", "m1", "--no-session"));
        argv.addAll(List.of(extraArgs));
        return AgentSession.create(ArgsParser.parse(argv.toArray(new String[0])),
            provider.register(),
            new ToolContext(System.getProperty("user.dir"), Map.of(),
                new DefaultShellExecutor(null), new DefaultFileSystem()));
    }

    @Test
    void successReturnsTrimmedTextAndSendsBugPromptShape() throws Exception {
        var provider = new ScriptedProvider().script(Kind.TEXT, "  # Report\nBody  ");
        try (var session = session(provider)) {
            var summary = session.summarizeForBugReport("  it broke  ");

            assertThat(summary).isEqualTo("# Report\nBody");
            var prompt = provider.userTexts.getLast();
            // prompt 四段：<conversation> 包裹＋<user-report>（trim 后）＋指令尾。
            assertThat(prompt).contains("<conversation>\n");
            assertThat(prompt).contains("<user-report>\nit broke\n</user-report>");
            assertThat(prompt).endsWith(BugReport.BUG_SUMMARY_INSTRUCTIONS);
            assertThat(prompt).doesNotContain("Note: only the last");
            // 系统提示词【模型可见】逐字在请求里（经 Context 传递，本 provider 不见——
            // 钉 maxTokens 臂：目录空 ⇒ min(4096, ∞)＝4096）。
            assertThat(provider.maxTokensSeen.getLast()).isEqualTo(4_096);
        }
    }

    @Test
    void noHintOmitsUserReportBlock() throws Exception {
        var provider = new ScriptedProvider().script(Kind.TEXT, "s");
        try (var session = session(provider)) {
            session.summarizeForBugReport(null);
            assertThat(provider.userTexts.getLast()).doesNotContain("<user-report>");
        }
    }

    @Test
    void abortedThrowsCancelled() throws Exception {
        var provider = new ScriptedProvider().script(Kind.ABORTED, null);
        try (var session = session(provider)) {
            assertThat(catchThrowable(() -> session.summarizeForBugReport(null)))
                .hasMessage("Bug report summary was cancelled");
        }
    }

    @Test
    void errorThrowsSummarizationFailure() throws Exception {
        var provider = new ScriptedProvider().script(Kind.ERROR, "boom");
        try (var session = session(provider)) {
            assertThat(catchThrowable(() -> session.summarizeForBugReport(null)))
                .hasMessage("Bug report summary failed: boom");
        }
    }

    @Test
    void toolCallThrows() throws Exception {
        var provider = new ScriptedProvider().script(Kind.TOOL_CALL, null);
        try (var session = session(provider)) {
            assertThat(catchThrowable(() -> session.summarizeForBugReport(null)))
                .hasMessage("Bug report summary attempted to call a tool");
        }
    }

    @Test
    void emptyTextThrows() throws Exception {
        var provider = new ScriptedProvider().script(Kind.EMPTY, null);
        try (var session = session(provider)) {
            assertThat(catchThrowable(() -> session.summarizeForBugReport(null)))
                .hasMessage("Bug report summary was empty");
        }
    }

    // pi 的「No model selected」臂（model == null ⇒ throw）保留在
    // BugReportSummarizer.summarize 首句——但经 AgentSession.create 的公共装配
    // 路径 harness 恒有模型解析结果（lane.model 非 null），夹具够不到该臂，
    // 如实登记不造假态。
}
