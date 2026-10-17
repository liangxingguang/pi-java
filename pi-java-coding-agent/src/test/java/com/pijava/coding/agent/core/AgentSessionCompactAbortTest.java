package com.pijava.coding.agent.core;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
import com.pijava.coding.agent.core.AgentSessionEvent.CompactionReason;
import com.pijava.coding.agent.core.session.InMemorySessionRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * #17（pi {@code agent-session.ts:2764-2765}）：手动 {@code compact()} 开头即
 * {@code await this.abort()} —— 中止在飞 run 并<b>等它落地</b>才进压缩，
 * 压缩读到的转录因此不含半截消息。
 */
class AgentSessionCompactAbortTest {

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

    /**
     * ①③：run 在飞时手动压缩 ⇒ 先 abort、等 run 完整落地（agent_end 先于
     * compaction_start），随后压缩正常完成 —— 顺序本身就是「压缩没读到半截
     * 转录」的证据（waitForIdle 在 {@code run.done()} 上 join）。
     *
     * <p><b>批 2 起先喂一轮完整历史再挂第二轮</b>：#58 删除 stopReason 投影剔除后，
     * 被 abort 的空内容助手消息留在投影里、字符估算为 0 —— 切点扫描会落在 user 上，
     * 若全场只有这一条 user ⇒ 历史为空 ⇒ pi 同形状地报 "Nothing to compact"
     * （现行 pi 主流本就把 aborted 消息计入投影，空历史就是不可压缩）。先前能过是
     * 旧剔除把 aborted 消息变成「被 omission」、恢复后缀规则把切点推过它 —— 那个
     * 前提已随 isContextMessage 的删除作废。先完成的种子轮让历史恒非空，
     * 「abort-first ＋ 等待」的断言与 abort 落在哪一帧（内容空/非空）脱钩。</p>
     */
    @Test
    void compactAbortsInFlightRunAndWaitsBeforeCompacting(
            @TempDir java.nio.file.Path tmp) throws Exception {
        var started = new CountDownLatch(1);
        var stop = new AtomicBoolean();
        var providers = ProviderRegistry.create();
        providers.register(new GatedProvider("faux-gated-compact", started, stop));
        var args = ArgsParser.parse(new String[] {
            "--provider", "faux-gated-compact", "--model", "hello", "--no-session"});
        var session = AgentSession.create(args, InMemorySessionRepository.create(), providers,
            new ToolContext(tmp.toString(), Map.of(),
                new DefaultShellExecutor(), new DefaultFileSystem()));
        try (session) {
            var events = new CopyOnWriteArrayList<AgentSessionEvent>();
            session.subscribe(events::add);
            // 种子轮：完整落地的一轮（call 1 立即给全文），让压缩历史恒非空。
            session.processPrompt("seed").statusFuture().get(20, TimeUnit.SECONDS);
            session.processPrompt("go");
            assertThat(started.await(10, TimeUnit.SECONDS)).as("run 已在飞（流挂住）").isTrue();

            // 主线程手动压缩：abort 在飞 run → waitForIdle → 才进压缩（pi 的 await abort()）。
            session.compact(SETTINGS);
            // 放走 fake 的生产线程 —— 循环侧早已 cutShort 离场，这里只是不让它空转。
            stop.set(true);

            int agentEnd = indexOf(events, AgentSessionEvent.AgentEnd.class);
            int compactionStart = indexOf(events, AgentSessionEvent.CompactionStart.class);
            assertThat(agentEnd).as("在飞 run 被 abort 后收口").isNotNegative();
            assertThat(compactionStart)
                .as("压缩只在 run 落地之后开始（abort-first ＋ 等待语义）")
                .isGreaterThan(agentEnd);
            var ends = events.stream()
                .filter(e -> e instanceof AgentSessionEvent.CompactionEnd)
                .map(e -> (AgentSessionEvent.CompactionEnd) e).toList();
            assertThat(ends).as("整场只有这一次（手动）压缩").hasSize(1);
            assertThat(ends.get(0).reason()).isEqualTo(CompactionReason.MANUAL);
            assertThat(ends.get(0).errorMessage()).as("压缩正常完成").isNull();
        } finally {
            stop.set(true);
        }
    }

    /** ②：空闲车道手动压缩 ⇒ 不产生任何 abort 痕迹，直过（waitForIdle 即返）。 */
    @Test
    void compactOnIdleLaneGoesStraightThrough(@TempDir java.nio.file.Path tmp) throws Exception {
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.sequence("faux-idle-compact",
            List.of(textSeq("hello"), textSeq("SUMMARY"))));
        var args = ArgsParser.parse(new String[] {
            "--provider", "faux-idle-compact", "--model", "hello", "--no-session"});
        var session = AgentSession.create(args, InMemorySessionRepository.create(), providers,
            new ToolContext(tmp.toString(), Map.of(),
                new DefaultShellExecutor(), new DefaultFileSystem()));
        try (session) {
            session.processPrompt("go").statusFuture().get(20, TimeUnit.SECONDS);

            var events = new CopyOnWriteArrayList<AgentSessionEvent>();
            session.subscribe(events::add);
            session.compact(SETTINGS);

            assertThat(events)
                .as("没有 run 被 abort —— 空闲直过，无 agent_end 痕迹")
                .noneMatch(e -> e instanceof AgentSessionEvent.AgentEnd);
            assertThat(events).anyMatch(e -> e instanceof AgentSessionEvent.CompactionStart);
            var ends = events.stream()
                .filter(e -> e instanceof AgentSessionEvent.CompactionEnd)
                .map(e -> (AgentSessionEvent.CompactionEnd) e).toList();
            assertThat(ends).hasSize(1);
            assertThat(ends.get(0).reason()).isEqualTo(CompactionReason.MANUAL);
            assertThat(ends.get(0).errorMessage()).as("压缩正常完成").isNull();
        }
    }

    private static int indexOf(List<AgentSessionEvent> events, Class<? extends AgentSessionEvent> type) {
        for (int i = 0; i < events.size(); i++) {
            if (type.isInstance(events.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 第 1 个请求（种子轮）立即给完整文本；第 2 个挂住 run：起手发 {@code Start}
     * 后只发心跳帧 —— pi-java 的 abort 在<b>拉取侧</b>两帧之间生效
     * （{@code PiLoopRunner} 的 cutShort），心跳让循环总能在 25ms 内观察到中止；
     * 之后（压缩的摘要请求）立即给完整文本。
     */
    private static final class GatedProvider implements Provider {
        private final String name;
        private final CountDownLatch started;
        private final AtomicBoolean stop;
        private final AtomicInteger calls = new AtomicInteger();

        GatedProvider(String name, CountDownLatch started, AtomicBoolean stop) {
            this.name = name;
            this.started = started;
            this.stop = stop;
        }

        @Override public String name() { return name; }
        @Override public String displayName() { return name; }
        @Override public Set<Class<? extends ProviderApi>> supportedApis() {
            return Set.of(ChatApi.class);
        }
        @Override
        @SuppressWarnings("unchecked")  // apiType 相等性保证 T 就是 ChatApi（FauxProvider 同式）
        public <T extends ProviderApi> T createApi(Class<T> apiType, ApiOptions options) {
            if (apiType.equals(ChatApi.class)) {
                return (T) new GatedChatApi();
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
                int call = calls.incrementAndGet();
                if (call == 1) {
                    textSeq("seed answer").forEach(publisher::submit);
                    return;
                }
                if (call > 2) {
                    textSeq("SUMMARY").forEach(publisher::submit);
                    return;
                }
                publisher.submit(new StreamEvent.Start(AssistantMessage.empty()));
                started.countDown();
                while (!stop.get()) {
                    publisher.submit(new StreamEvent.TextDelta(0, "x", AssistantMessage.empty()
                        .withContent(List.of(new ContentBlock.TextContent("x")))));
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
