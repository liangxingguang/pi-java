package com.pijava.coding.agent.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ProviderApi;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.ModelCatalog;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.protocol.AbstractChatApi;
import com.pijava.ai.provider.Provider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.session.InMemorySessionRepository;
import com.pijava.coding.agent.core.slash.CommandRegistry;
import com.pijava.coding.agent.core.slash.SlashContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 批 15（T12）核销核验：C-13 {@code /changelog}（批 12 已落）与 C-21
 * {@code /compact}（abort-first，agent-core 批 1 #17 已落）——代码不动，
 * 只补钉形测试翻判定。
 */
class SlashChangelogCompactTest {

    /** C-13：/changelog 形状钉——{@code What's New} 头＋全量条目倒序拼接（pi
     *  :6774-6787；渲染差 D63 在册）。 */
    @Test
    void changelogIsHeaderPlusReversedEntries() throws Exception {
        var result = CommandRegistry.withBuiltins()
            .dispatch("/changelog", SlashContext.of(null))
            .toCompletableFuture().get(10, TimeUnit.SECONDS);

        var entries = ChangelogSupport.parse(ChangelogSupport.loadBundledUnchecked());
        assertThat(entries).as("bundled CHANGELOG 至少一条").isNotEmpty();
        var reversed = new java.util.ArrayList<>(entries);
        java.util.Collections.reverse(reversed);
        var expected = "What's New\n\n" + reversed.stream()
            .map(ChangelogSupport.ChangelogEntry::content)
            .collect(java.util.stream.Collectors.joining("\n\n"));
        assertThat(result).isEqualTo(expected);
    }

    /** C-21：运行中 /compact ⇒ 先 abort（pi compact() 首句 await this.abort()
     *  对位 abortAndWaitForIdle——agent_end 先于 CompactionStart）。
     *  会话级同案见 {@code AgentSessionCompactAbortTest}（本钉命令 dispatch 路）。
     *  keepRecentTokens=1 经 fake home 的 settings.json 注入（同案 SETTINGS 对位）。 */
    @Test
    void compactDispatchAbortsInFlightRunFirst(@TempDir Path tmp) throws Exception {
        var realHome = System.getProperty("user.home");
        System.setProperty("user.home", tmp.toString());
        var agentDir = tmp.resolve(".pi-java").resolve("agent");
        java.nio.file.Files.createDirectories(agentDir);
        // enabled:false 关自动（手动 /compact 不吃 enabled 门——pi 同，AgentSession
        // :1256 注）；keepRecentTokens:1 让小历史可压（同案 SETTINGS 对位）。
        java.nio.file.Files.writeString(agentDir.resolve("settings.json"),
            "{\"compaction\":{\"enabled\":false,\"keepRecentTokens\":1}}");
        var started = new CountDownLatch(1);
        var stop = new AtomicBoolean();
        var providers = ProviderRegistry.create();
        providers.register(new GatedProvider("gated", started, stop));
        try (var session = AgentSession.create(ArgsParser.parse(new String[] {
                    "--provider", "gated", "--model", "hello", "--no-session"}),
                InMemorySessionRepository.create(), providers,
                new ToolContext(tmp.toString(), Map.of(),
                    new DefaultShellExecutor(), new DefaultFileSystem()))) {
            var events = new CopyOnWriteArrayList<AgentSessionEvent>();
            session.subscribe(events::add);
            // 种子轮完整落地（AgentSessionCompactAbortTest 同形）⇒ 压缩历史非空。
            session.processPrompt("seed").statusFuture().get(20, TimeUnit.SECONDS);
            session.processPrompt("go");
            assertThat(started.await(10, TimeUnit.SECONDS)).as("run 在飞").isTrue();

            var stage = CommandRegistry.withBuiltins()
                .dispatch("/compact", SlashContext.of(session)).toCompletableFuture();
            var result = stage.get(20, TimeUnit.SECONDS);
            stop.set(true);

            assertThat(result).isEqualTo("Compacted.");
            int agentEnd = indexOf(events, AgentSessionEvent.AgentEnd.class);
            int compactionStart = indexOf(events, AgentSessionEvent.CompactionStart.class);
            assertThat(agentEnd).as("在飞 run 被 abort 后收口——事件流：%s",
                events.stream().map(e -> e.getClass().getSimpleName()).toList())
                .isNotNegative();
            assertThat(compactionStart)
                .as("abort-first：压缩只在 run 落地之后开始")
                .isGreaterThan(agentEnd);
        } finally {
            stop.set(true);
            System.setProperty("user.home", realHome);
        }
    }

    private static int indexOf(java.util.List<AgentSessionEvent> events, Class<?> type) {
        for (int i = 0; i < events.size(); i++) {
            if (type.isInstance(events.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /** 计数键夹具（AgentSessionCompactAbortTest 的 GatedProvider 同形）：
     *  call 1 立即落地；call 2 挂流（心跳提交让 abort 关流可觉）直到 stop；
     *  call 3+（摘要）直给。 */
    private static final class GatedProvider implements Provider {
        private final String name;
        private final CountDownLatch started;
        private final AtomicBoolean stop;
        private final java.util.concurrent.atomic.AtomicInteger calls =
            new java.util.concurrent.atomic.AtomicInteger();

        GatedProvider(String name, CountDownLatch started, AtomicBoolean stop) {
            this.name = name;
            this.started = started;
            this.stop = stop;
        }

        @Override public String name() { return name; }
        @Override public String displayName() { return name; }
        @Override public Set<Class<? extends ProviderApi>> supportedApis() {
            return Set.of(com.pijava.ai.api.ChatApi.class);
        }

        @Override
        public <T extends ProviderApi> T createApi(Class<T> apiType, ApiOptions options) {
            return apiType.cast(new Api());
        }

        @Override public ModelCatalog builtinModels() { return ModelCatalog.empty(); }

        private static java.util.List<StreamEvent> textSeq(String text) {
            var done = AssistantMessage.empty()
                .withContent(java.util.List.of(new ContentBlock.TextContent(text)))
                .withStopReason(StopReason.STOP);
            return java.util.List.of(
                new StreamEvent.Start(AssistantMessage.empty()),
                new StreamEvent.TextStart(0, AssistantMessage.empty()),
                new StreamEvent.TextDelta(0, text, done),
                new StreamEvent.StreamDone(StopReason.STOP, null, done));
        }

        private final class Api extends AbstractChatApi {
            @Override public String apiName() { return "gated"; }
            @Override protected boolean resolvesRequestOptions() { return false; }

            @Override
            protected void streamInternal(StreamRequest request,
                    java.util.concurrent.SubmissionPublisher<StreamEvent> publisher) {
                int call = calls.incrementAndGet();
                if (call == 1) {
                    textSeq("seed answer").forEach(publisher::submit);
                    return;
                }
                if (call > 2) {
                    // 摘要（call 3+）直给——挂住的只有 call 2 的主 run。
                    textSeq("SUMMARY").forEach(publisher::submit);
                    return;
                }
                publisher.submit(new StreamEvent.Start(AssistantMessage.empty()));
                started.countDown();
                while (!stop.get()) {
                    publisher.submit(new StreamEvent.TextDelta(0, "x",
                        AssistantMessage.empty().withContent(
                            java.util.List.of(new ContentBlock.TextContent("x")))));
                    try {
                        Thread.sleep(25);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }
}
