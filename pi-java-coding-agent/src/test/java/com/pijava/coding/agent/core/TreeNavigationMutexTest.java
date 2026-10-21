package com.pijava.coding.agent.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
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
import com.pijava.coding.agent.cli.ArgsParser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * docs/18 终审 Critical ①：分支摘要在飞 ⇒ {@code isCompacting()} 为 true
 * （pi {@code isCompacting} getter 含 {@code _branchSummaryAbortController}，
 * agent-session.ts:1622-1630）——重入导航被双门第二句挡下（不再 NPE/撕转录）、
 * prompt 路由门同生效（pi :1940-1942 逐字）。摘要用闩门控，时序确定。
 */
class TreeNavigationMutexTest {

    /** 闩控 provider：{@code <conversation>} 请求＝摘要类调用，可闩住等测试放行。 */
    static final class GatedChatProvider implements Provider {
        private final String name;
        private final Queue<String> replies = new ConcurrentLinkedQueue<>();
        private volatile CountDownLatch summaryStarted;
        private volatile CountDownLatch summaryGate;

        GatedChatProvider(String name) {
            this.name = name;
        }

        void enqueue(String text) {
            replies.add(text);
        }

        void armSummaryGate() {
            summaryStarted = new CountDownLatch(1);
            summaryGate = new CountDownLatch(1);
        }

        void awaitSummaryStarted() throws InterruptedException {
            assertThat(summaryStarted.await(15, TimeUnit.SECONDS)).as("摘要调用发起").isTrue();
        }

        void releaseSummary() {
            summaryGate.countDown();
        }

        @Override public String name() { return name; }
        @Override public String displayName() { return name; }
        @Override public Set<Class<? extends ProviderApi>> supportedApis() {
            return Set.of(com.pijava.ai.api.ChatApi.class);
        }

        @Override
        public <T extends ProviderApi> T createApi(Class<T> apiType, ApiOptions options) {
            return apiType.cast(new Api(this));
        }

        @Override public ModelCatalog builtinModels() { return ModelCatalog.empty(); }

        private static final class Api extends AbstractChatApi {
            private final GatedChatProvider provider;

            Api(GatedChatProvider provider) {
                this.provider = provider;
            }

            @Override public String apiName() { return "gated"; }
            @Override protected boolean resolvesRequestOptions() { return false; }

            @Override
            protected void streamInternal(StreamRequest request,
                    java.util.concurrent.SubmissionPublisher<StreamEvent> publisher) {
                boolean summarization = false;
                for (Message message : request.messages()) {
                    if (message instanceof Message.UserMessage user) {
                        var parts = new java.util.ArrayList<String>();
                        for (ContentBlock block : user.content()) {
                            if (block instanceof ContentBlock.TextContent text) {
                                parts.add(text.text());
                            }
                        }
                        if (String.join("\n", parts).contains("<conversation>")) {
                            summarization = true;
                        }
                    }
                }
                if (summarization) {
                    var started = provider.summaryStarted;
                    if (started != null) {
                        started.countDown();
                    }
                    var gate = provider.summaryGate;
                    if (gate != null) {
                        try {
                            gate.await(15, TimeUnit.SECONDS); // 防挂死：超时放行
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
                String text = provider.replies.poll();
                if (text == null) {
                    text = "ok";
                }
                var msg = AssistantMessage.empty().withContent(List.of(
                    new ContentBlock.TextContent(text))).withStopReason(StopReason.STOP);
                publisher.submit(new StreamEvent.Start(AssistantMessage.empty()));
                publisher.submit(new StreamEvent.TextDelta(0, text,
                    msg.withStopReason((StopReason) null)));
                publisher.submit(StreamEvent.StreamDone.settle(StopReason.STOP, msg));
            }
        }
    }

    @TempDir
    Path tmp;

    private record Rig(AgentSession session, GatedChatProvider provider)
            implements AutoCloseable {
        @Override
        public void close() {
            session.close();
        }
    }

    private Rig rig(Path sessionDir) throws Exception {
        var provider = new GatedChatProvider("gated");
        provider.enqueue("r1");
        provider.enqueue("r2");
        var args = ArgsParser.parse(new String[] {"--provider", "gated", "--model", "hello",
            "--session-dir", sessionDir.toString()});
        var providers = ProviderRegistry.create();
        providers.register(provider);
        var session = AgentSession.create(args,
            PersistentSessionRepositories.jsonl(sessionDir), providers,
            new ToolContext(tmp.toString(), Map.of(),
                new DefaultShellExecutor(), new DefaultFileSystem()),
            SettingsManager.load(args.projectTrustOverride()));
        session.services().settings().accessors().setCompactionEnabled(false);
        session.processPrompt("first question").status();
        session.processPrompt("second question").status();
        return new Rig(session, provider);
    }

    private static String firstUserEntryId(AgentSession session) {
        return session.accumulatedEntries().stream()
            .filter(e -> e instanceof Entry.Message m && "user".equals(m.message().role()))
            .map(Entry::id).findFirst().orElseThrow();
    }

    /** 闩在飞的后台摘要导航；返回 worker 句柄与结局槽。 */
    private record PendingNav(Thread worker, AtomicReference<AgentSession.TreeNavigation> result,
                              AtomicReference<Throwable> error) {}

    private PendingNav navigateInBackground(AgentSession session, String target) {
        var result = new AtomicReference<AgentSession.TreeNavigation>();
        var error = new AtomicReference<Throwable>();
        var worker = Thread.startVirtualThread(() -> {
            try {
                result.set(session.navigateTree(target, true, null, false, null));
            } catch (Throwable t) {
                error.set(t);
            }
        });
        return new PendingNav(worker, result, error);
    }

    /** ①a：摘要在飞 ⇒ isCompacting＝true；重入导航吃双门第二句（不再字段覆写/NPE）。 */
    @Test
    void branchSummaryInFlightCountsAsCompactingAndBlocksReentry() throws Exception {
        Path dir = Files.createTempDirectory("pi-tree-mutex");
        try (var rig = rig(dir)) {
            var session = rig.session();
            rig.provider().armSummaryGate();
            String target = firstUserEntryId(session);
            var pending = navigateInBackground(session, target);
            rig.provider().awaitSummaryStarted();

            assertThat(session.isCompacting())
                .as("pi :1622-1630：分支摘要在飞即 isCompacting").isTrue();
            assertThatThrownBy(() -> session.navigateTree(target, false, null, false, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Wait for the current compaction or tree navigation to finish"
                    + " before navigating the session tree.");

            rig.provider().releaseSummary();
            pending.worker().join(15_000);
            assertThat(pending.error().get()).isNull();
            assertThat(pending.result().get().cancelled()).isFalse();
            assertThat(pending.result().get().summaryEntry()).isNotNull();
        }
    }

    /** ①b：摘要在飞 ⇒ prompt 路由门抛 pi :1940-1942 逐字错文（不再起跑新 run）。 */
    @Test
    void promptDuringBranchSummaryThrowsVerbatim() throws Exception {
        Path dir = Files.createTempDirectory("pi-tree-prompt-gate");
        try (var rig = rig(dir)) {
            var session = rig.session();
            rig.provider().armSummaryGate();
            String target = firstUserEntryId(session);
            var pending = navigateInBackground(session, target);
            rig.provider().awaitSummaryStarted();

            assertThatThrownBy(() -> session.processPrompt("mid-summary"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(PromptRouter.COMPACTING_MESSAGE);
            assertThat(session.isRunning()).as("不得起跑新 run").isFalse();

            rig.provider().releaseSummary();
            pending.worker().join(15_000);
            assertThat(pending.error().get()).isNull();
            // 窗口过后路由恢复：prompt 照常驱动。
            session.processPrompt("after summary").status();
            assertThat(session.accumulatedEntries().stream()
                    .filter(e -> e instanceof Entry.Message m
                        && "user".equals(m.message().role())))
                .hasSize(3);
        }
    }
}
