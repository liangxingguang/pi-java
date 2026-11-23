package com.pijava.coding.agent.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.pijava.agent.compaction.CompactionSettings;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ChatApi;
import com.pijava.ai.api.ProviderApi;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.ModelCatalog;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.protocol.AbstractChatApi;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.provider.Provider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.session.InMemorySessionRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L5 W5（pi agent-session.ts:3829-3835 对位）：会话层 reload 双门——直接调
 * {@code session.reload()}（扩展/宿主，绕过命令层）也拒绝在流式或压缩中重载，
 * 文案与命令层逐字一致（已拦截的命令层见 {@code SessionReloadTest}）。
 */
class SessionReloadSessionGateTest {

    private static final CompactionSettings SETTINGS = new CompactionSettings(true, 16_384, 1);

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

    @Test
    void directReloadRefusesWhileARunIsInFlight(@TempDir Path tmp) throws Exception {
        var providers = ProviderRegistry.create();
        // 每帧 300ms ⇒ run 窗口可观察（RpcDispositionWireTest 同式夹具）。
        providers.register(FauxProvider.sequence("faux-gate", List.of(textSeq("ok")), 300));
        var args = ArgsParser.parse(new String[] {
            "--provider", "faux-gate", "--model", "hello", "--no-session"});
        try (var s = AgentSession.create(args, InMemorySessionRepository.create(), providers,
                new ToolContext(tmp.toString(), Map.of(),
                    new DefaultShellExecutor(), new DefaultFileSystem()))) {
            var run = s.processPrompt("hi");
            try {
                var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!s.isRunning() && System.nanoTime() < deadline) {
                    Thread.sleep(5);
                }
                assertThat(s.isRunning()).as("run 窗口已开").isTrue();
                assertThatThrownBy(s::reload).isInstanceOf(IllegalStateException.class)
                    .hasMessage("Wait for the current response to finish before reloading.");
            } finally {
                s.abort();
                run.statusFuture().get(15, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void directReloadRefusesWhileCompacting(@TempDir Path tmp) throws Exception {
        var provider = new SummaryGateProvider();
        var providers = ProviderRegistry.create();
        providers.register(provider);
        var args = ArgsParser.parse(new String[] {
            "--provider", "faux-summary-gate", "--model", "hello", "--no-session"});
        try (var s = AgentSession.create(args, InMemorySessionRepository.create(), providers,
                new ToolContext(tmp.toString(), Map.of(),
                    new DefaultShellExecutor(), new DefaultFileSystem()))) {
            s.processPrompt("seed").statusFuture().get(20, TimeUnit.SECONDS);
            var compaction = Thread.startVirtualThread(() -> s.compact(SETTINGS));
            try {
                var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!s.isCompacting() && System.nanoTime() < deadline) {
                    Thread.sleep(5);
                }
                assertThat(s.isCompacting()).as("压缩窗口已开").isTrue();
                assertThatThrownBy(s::reload).isInstanceOf(IllegalStateException.class)
                    .hasMessage("Wait for compaction to finish before reloading.");
            } finally {
                provider.release.countDown();
                compaction.join(TimeUnit.SECONDS.toMillis(15));
            }
        }
    }

    /**
     * 第 1 个请求（种子轮）立即给全文；摘要请求（第 2 个）挂起在 {@code release} 上，
     * 让压缩窗口可确定观察（AgentSessionCompactAbortTest 的闸式 provider 同旨）。
     */
    private static final class SummaryGateProvider implements Provider {
        final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger calls = new AtomicInteger();

        @Override public String name() { return "faux-summary-gate"; }
        @Override public String displayName() { return name(); }
        @Override public Set<Class<? extends ProviderApi>> supportedApis() {
            return Set.of(ChatApi.class);
        }

        @Override public <T extends ProviderApi> T createApi(Class<T> apiType, ApiOptions options) {
            if (apiType.equals(ChatApi.class)) {
                return apiType.cast(new GatedChatApi());
            }
            throw new IllegalArgumentException("Unsupported API type: " + apiType);
        }

        @Override public ModelCatalog builtinModels() { return ModelCatalog.empty(); }

        private final class GatedChatApi extends AbstractChatApi {
            @Override public String apiName() { return "faux"; }
            @Override protected boolean resolvesRequestOptions() { return false; }

            @Override
            protected void streamInternal(StreamRequest request,
                    SubmissionPublisher<StreamEvent> publisher) {
                if (calls.incrementAndGet() == 1) {
                    textSeq("seed answer").forEach(publisher::submit);
                    return;
                }
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                textSeq("SUMMARY").forEach(publisher::submit);
            }
        }
    }
}
