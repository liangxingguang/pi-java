package com.pijava.coding.agent.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.ai.Usage;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.session.InMemorySessionRepository;

/**
 * 批 13（docs/24 §7，T5/T6/T8）：cache-warmer 的装配与四面接线——
 * <ul>
 *   <li>warming wrapper 只包 harness 注入点（裁决①）：prompt 触发 start；
 *       摘要路径（手动 compact）不触发；</li>
 *   <li>settled ⇒ onAgentSettled（streaming 模式逐字理由）；closeWith ⇒ cancel；
 *       setCacheWarmingMode ⇒ settings 写入＋onModeChanged；</li>
 *   <li>{@code appendUsage} 收口：九键形状＋EntryAppended 广播＋JSONL 落盘
 *       （{@code "kind":"cache_warm"}，pi 同形可跨读）＋footer addTokens；</li>
 *   <li>run 中 append ⇒ deferred write 路径（PiLaneSink 同机制）。</li>
 * </ul>
 */
class AgentSessionCacheWarmingTest {

    @TempDir
    Path tmp;

    private String savedHome;

    @BeforeEach
    void isolateHome() {
        // settings 懒解析坑（批 12 教训）：user.home 须保持到 tearDown 之后。
        savedHome = System.getProperty("user.home");
        System.setProperty("user.home", tmp.resolve("home").toString());
    }

    @AfterEach
    void restoreHome() {
        if (savedHome != null) {
            System.setProperty("user.home", savedHome);
        }
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

    private ToolContext toolContext() {
        return new ToolContext(tmp.toString(), Map.of(),
            new DefaultShellExecutor(), new DefaultFileSystem());
    }

    /** faux provider 顶名 "anthropic"——模型 id 取真目录的 claude-opus-4-6
     *  （promptCache/价目/compat 全真，warmer 的目录查询走 {@code catalog::find}）。 */
    private AgentSession anthropicSession(List<List<StreamEvent>> seqs) {
        var args = ArgsParser.parse(new String[] {
            "--provider", "anthropic", "--model", "claude-opus-4-6", "--no-session"});
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.sequence("anthropic", seqs));
        return AgentSession.create(args, InMemorySessionRepository.create(),
            providers, toolContext());
    }

    private static void drive(AgentSession session, String prompt) throws Exception {
        session.processPrompt(prompt).statusFuture().get(30, TimeUnit.SECONDS);
    }

    /** ① 装配：prompt 触发 start；streaming 模式 settled ⇒ onAgentSettled 逐字理由。 */
    @Test
    void promptTriggersWarmerStartAndSettledStopsIt() throws Exception {
        try (var session = anthropicSession(List.of(textSeq("hi")))) {
            assertThat(session.cacheWarmingStatus()).as("warmer 装配在").isNotNull();

            drive(session, "go");

            var status = session.cacheWarmingStatus();
            assertThat(status.state()).isEqualTo("inactive");
            assertThat(status.reason()).isEqualTo("agent run settled");
        }
    }

    /** ② 裁决①：摘要路径（手动 compact 的 LlmSummaryGenerator）不触发 start。 */
    @Test
    void summaryPathDoesNotRetriggerWarmerStart() throws Exception {
        try (var session = anthropicSession(List.of(
                textSeq("one"), textSeq("two"), textSeq("summary")))) {
            drive(session, "first");
            drive(session, "second");
            assertThat(session.cacheWarmingStatus().reason()).isEqualTo("agent run settled");

            // 小 keep 预算 ⇒ 两轮短历史即可成 plan（AgentSessionCompactAbortTest 同款夹具）。
            session.compact(new com.pijava.agent.compaction.CompactionSettings(
                true, 16_384, 1));

            // 摘要请求若误走 warming wrapper，start 会重排 run ⇒ 理由变成
            // "cache economics unavailable"（faux 零用量）；留在 settled 理由即未触发。
            assertThat(session.cacheWarmingStatus().reason())
                .as("摘要路径不触发 start（裁决①）")
                .isEqualTo("agent run settled");
        }
    }

    /** ③ dispose：closeWith ⇒ cancel（pi :1409-1412 对位）。 */
    @Test
    void closeCancelsWarmer() throws Exception {
        var session = anthropicSession(List.of(textSeq("hi")));
        drive(session, "go");
        session.close();
        assertThat(session.cacheWarmingStatus().reason()).isEqualTo("inactive");
    }

    /** ④ 写入口：settings 写入＋onModeChanged（status 的 off 门逐字）。 */
    @Test
    void setCacheWarmingModeWritesSettingsAndReconciles() {
        try (var session = anthropicSession(List.of(textSeq("hi")))) {
            session.setCacheWarmingMode("off");
            assertThat(session.services().settings().network().getCacheWarmingMode())
                .isEqualTo("off");
            assertThat(session.cacheWarmingStatus().reason())
                .isEqualTo("cache warming disabled");

            session.setCacheWarmingMode("idle");
            assertThat(session.services().settings().network().getCacheWarmingMode())
                .isEqualTo("idle");
        }
    }

    /** ⑤ appendUsage 收口：九键形状＋EntryAppended 广播＋JSONL 落盘＋footer addTokens。 */
    @Test
    void appendUsageAppendsBroadcastsPersistsAndCountsTokens() throws Exception {
        var dir = Files.createDirectory(tmp.resolve("sessions-warm"));
        var args = ArgsParser.parse(new String[] {
            "--provider", "faux-warm", "--model", "hello"});
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.sequence("faux-warm", List.of()));
        var handle = PersistentSessionRepositories.jsonl(dir);
        try (var session = AgentSession.create(args, handle, providers, toolContext(),
                SettingsManager.load(args.projectTrustOverride()))) {
            var events = new CopyOnWriteArrayList<AgentSessionEvent>();
            try (var sub = session.subscribe(events::add)) {
                var usage = new Usage(0, 1, 100, 0, null, null, 101,
                    new Usage.Cost(0, 0, 0.01, 0, 0.01));
                var entry = session.appendUsage("cache_warm", "faux-warm", "hello",
                    usage, "extension override");

                // 九键形状（pi UsageEntry 逐字；审计五键空——非车道产出）。
                assertThat(entry.kind()).isEqualTo("cache_warm");
                assertThat(entry.provider()).isEqualTo("faux-warm");
                assertThat(entry.model()).isEqualTo("hello");
                assertThat(entry.usage()).isSameAs(usage);
                assertThat(entry.note()).isEqualTo("extension override");
                assertThat(entry.runId()).isNull();
                assertThat(entry.entryId()).isNull();
                // 广播（裁决⑧：收口内建，pi onWarmed 不移植）。
                assertThat(events).anyMatch(e -> e instanceof AgentSessionEvent.EntryAppended a
                    && a.entry() == entry);
                // footer addTokens（与 PiLaneSink 同一计数器）。
                try (var watch = session.harness().watchSession()) {
                    assertThat(watch.current().totalTokens()).isEqualTo(101);
                }
            }
        }
        // JSONL 落盘（pi 同形，可跨读）。
        try (var files = Files.walk(dir)) {
            var jsonl = files.filter(p -> p.toString().endsWith(".jsonl")).toList();
            assertThat(jsonl).isNotEmpty();
            var lines = Files.readString(jsonl.get(0));
            assertThat(lines).contains("\"kind\":\"cache_warm\"")
                .contains("\"note\":\"extension override\"");
        }
    }

    /** ⑥ run 中 append（streaming 臂）⇒ deferred write 路径落车道（PiLaneSink 同机制）。 */
    @Test
    void appendUsageDuringRunUsesDeferredWritePath() throws Exception {
        try (var session = anthropicSession(List.of(textSeq("hi")))) {
            var once = new AtomicBoolean();
            var appended = new CopyOnWriteArrayList<Entry.Usage>();
            try (var sub = session.subscribe(event -> {
                // MessageUpdate 在 run 中同步发射——此处 append 即「run 中」臂。
                if (event instanceof AgentSessionEvent.MessageUpdate
                        && once.compareAndSet(false, true)) {
                    appended.add(session.appendUsage("cache_warm", "anthropic",
                        "claude-opus-4-6", Usage.of(100, 1, 101), null));
                }
            })) {
                drive(session, "go");
            }
            assertThat(appended).hasSize(1);
            var transcript = session.harness().snapshot(session.laneName()).transcript();
            assertThat(transcript).contains(appended.get(0));
        }
    }
}
