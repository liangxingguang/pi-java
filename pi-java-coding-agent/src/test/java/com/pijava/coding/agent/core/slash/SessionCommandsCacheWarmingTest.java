package com.pijava.coding.agent.core.slash;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.SettingsManager;
import com.pijava.coding.agent.core.session.InMemorySessionRepository;

/**
 * 批 13（docs/24 §7，T12）：{@code /session} 的 Cache Warming 段
 * （pi interactive-mode.ts:6739-6746 逐字——<b>无条件显示</b>，不吃
 * showCacheMissNotices 门）＋Cache Re-billed 行（:6757-6764；D68：java 无
 * Cost 块，挂 Cache Warming 段尾）。
 */
class SessionCommandsCacheWarmingTest {

    @TempDir
    Path tmp;

    private String savedHome;

    @BeforeEach
    void isolateHome() {
        savedHome = System.getProperty("user.home");
        System.setProperty("user.home", tmp.resolve("home").toString());
    }

    @AfterEach
    void restoreHome() {
        if (savedHome != null) {
            System.setProperty("user.home", savedHome);
        }
    }

    /** 带用量上报的 faux 流（终局 partial 背 UsageInfo——引擎据此盖 usage）。 */
    private static List<StreamEvent> usageSeq(String text, double input, double cacheRead,
                                              double cacheWrite) {
        var usage = new Usage(input, 1, cacheRead, cacheWrite, null, null,
            input + cacheRead + cacheWrite + 1, Usage.Cost.zero());
        var done = AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.TextContent(text)))
            .withStopReason(StopReason.STOP);
        done = done.withUsage(new StreamEvent.UsageInfo((long) input, 1, done, usage));
        return List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.StreamDone(StopReason.STOP, null, done));
    }

    private AgentSession anthropicSession(List<List<StreamEvent>> seqs) {
        var args = ArgsParser.parse(new String[] {
            "--provider", "anthropic", "--model", "claude-opus-4-6", "--no-session"});
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.sequence("anthropic", seqs));
        return AgentSession.create(args, providers,
            new ToolContext(tmp.toString(), Map.of(),
                new DefaultShellExecutor(), new DefaultFileSystem()));
    }

    private static String sessionInfo(AgentSession session) {
        var registry = CommandRegistry.withBuiltins();
        return registry.dispatch("/session", SlashContext.of(session))
            .toCompletableFuture().join();
    }

    /** 三态之一：无决策的 inactive（warmer 在、未跑过请求 ⇒ waiting for first request）。 */
    @Test
    void freshSessionShowsInactiveWaitingForFirstRequest() {
        try (var session = anthropicSession(List.of())) {
            var info = sessionInfo(session);
            assertThat(info).contains("Cache Warming\n")
                .contains("Mode: streaming\n")
                .contains("Status: Inactive (waiting for first request)\n")
                .doesNotContain("Cache miss penalty");
        }
    }

    /** 三态之二：settled 后（streaming 模式逐字理由）。 */
    @Test
    void settledRunShowsAgentRunSettled() throws Exception {
        try (var session = anthropicSession(List.of(usageSeq("hi", 100_000, 0, 0)))) {
            session.processPrompt("go").statusFuture().get(30, TimeUnit.SECONDS);
            var info = sessionInfo(session);
            assertThat(info).contains("Status: Inactive (agent run settled)\n");
        }
    }

    /** 三态之三：idle 模式＋经济学可用 ⇒ decision 两行（pi :6743-6746）。 */
    @Test
    void idleModeWithEconomicsShowsDecisionLines() throws Exception {
        try (var session = anthropicSession(List.of(usageSeq("hi", 100_000, 0, 0)))) {
            session.setCacheWarmingMode("idle");
            session.processPrompt("go").statusFuture().get(30, TimeUnit.SECONDS);

            var info = sessionInfo(session);
            assertThat(info).contains("Mode: idle\n");
            // opus-4-6 价目：miss = 100k×(6.25−0.5)/M = 0.575；warm = 100k×0.5/M＋25/M×1。
            assertThat(info).contains("Cache miss penalty: $0.575\n")
                .contains("Refresh cost: $0.050\n");
            // idle 相 ⇒ 续聊概率 0.15（pi IDLE_CONTINUATION_PROBABILITY）：
            // 0.15×0.575−0.050 = 0.036 < 0.050 ⇒ stop（pi 同形结局）。
            assertThat(info).containsPattern(
                "Status: Decision in .+15% continuation probability,"
                    + " expected savings \\$0\\.036 < \\$0\\.050 -> stop\\)\n");
        }
    }

    /** Cache Re-billed 行（:6757-6764；missedCost<0.0001 ⇒ 纯计数形）。 */
    @Test
    void cacheRebilledLineCountsTheWaste() throws Exception {
        try (var session = anthropicSession(List.of(
                usageSeq("one", 0, 0, 100_000),
                usageSeq("two", 100_000, 0, 0)))) {
            session.processPrompt("first").statusFuture().get(30, TimeUnit.SECONDS);
            session.processPrompt("second").statusFuture().get(30, TimeUnit.SECONDS);

            var info = sessionInfo(session);
            assertThat(info).contains("Cache Re-billed: 100,000 tokens, 1 miss\n");
        }
    }

    /** 无 miss ⇒ 无 Re-billed 行；无 waste 时段尾干净。 */
    @Test
    void noWasteNoRebilledLine() throws Exception {
        try (var session = anthropicSession(List.of(usageSeq("hi", 100_000, 0, 0)))) {
            session.processPrompt("go").statusFuture().get(30, TimeUnit.SECONDS);
            assertThat(sessionInfo(session)).doesNotContain("Cache Re-billed");
        }
    }
}
