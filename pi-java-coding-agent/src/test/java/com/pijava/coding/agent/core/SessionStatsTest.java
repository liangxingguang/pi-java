package com.pijava.coding.agent.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.Usage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.session.InMemorySessionRepository;
import com.pijava.coding.agent.core.slash.SlashContext;
import org.junit.jupiter.api.Test;

/**
 * 批 15（T11）：pi {@code getSessionStats}（agent-session.ts:4185-4230）与
 * {@code getUsageCostBreakdown}（usage-totals.ts:61-96）对拍——四源聚合、
 * breakdown 分组（responseModel 优先／Tools 桶／滤零／降序）；＋ /session 段序。
 */
class SessionStatsTest {

    private static Usage usage(double input, double output, double cacheRead,
                               double cacheWrite, double cost) {
        return new Usage(input, output, cacheRead, cacheWrite, null, null,
            input + output + cacheRead + cacheWrite,
            new Usage.Cost(0, 0, 0, 0, cost));
    }

    private AgentSession session() {
        return AgentSession.create(ArgsParser.parse(new String[] {"--no-session"}),
            InMemorySessionRepository.create());
    }

    @Test
    void emptySessionIsAllZero() throws Exception {
        try (var session = session()) {
            var stats = SessionStats.of(session);
            assertThat(stats.totalMessages()).isZero();
            assertThat(stats.tokens().total()).isZero();
            assertThat(stats.cost()).isZero();
            assertThat(UsageCostBreakdown.of(session.accumulatedEntries())).isEmpty();
        }
    }

    @Test
    void aggregatesAllFourSources() throws Exception {
        try (var session = session()) {
            // ① usage 条目。
            session.appendUsage("cache_warm", "anthropic", "claude-fable-5",
                usage(100, 0, 50, 10, 0.001), null);
            // ② assistant message usage＋toolUse 计数。
            var assistant = new Message.AssistantMessage(
                List.of(new ContentBlock.TextContent("t"),
                    new ContentBlock.ToolUseContent("tc1", "bash", Map.of())),
                StopReason.STOP, null, "anthropic-messages", "anthropic",
                "claude-fable-5", "claude-fable-5-20261001", null, null, null,
                usage(200, 40, 0, 0, 0.002), Instant.now(), null, null, null);
            session.appendBoundaryEntry(new Entry.Message(
                "a1", 0, null, Instant.now(), assistant, null));
            // ③ toolResult message usage。
            var toolResult = new Message.ToolResultMessage("tc1", "bash",
                List.of(new ContentBlock.TextContent("out")), null,
                usage(5, 0, 0, 0, 0.0005), List.of(), false, Instant.now(), null);
            session.appendBoundaryEntry(new Entry.Message(
                "t1", 0, null, Instant.now(), toolResult, null));
            // ④ branch_summary usage。
            session.appendBoundaryEntry(new Entry.BranchSummary(
                "b1", 0, null, Instant.now(), "a1", "summary", null,
                usage(20, 5, 0, 0, 0.0002), null));

            var stats = SessionStats.of(session);
            assertThat(stats.userMessages()).isZero();
            assertThat(stats.assistantMessages()).isEqualTo(1);
            assertThat(stats.toolCalls()).isEqualTo(1);
            assertThat(stats.toolResults()).isEqualTo(1);
            assertThat(stats.totalMessages()).isEqualTo(2);   // a1＋t1（branch_summary 非 message）
            assertThat(stats.tokens().input()).isEqualTo(325); // 100+200+5+20
            assertThat(stats.tokens().output()).isEqualTo(45);
            assertThat(stats.tokens().cacheRead()).isEqualTo(50);
            assertThat(stats.tokens().cacheWrite()).isEqualTo(10);
            assertThat(stats.tokens().total()).isEqualTo(430);
            assertThat(stats.cost()).isEqualTo(0.0037,
                org.assertj.core.api.Assertions.within(1e-9));

            var breakdown = UsageCostBreakdown.of(session.accumulatedEntries());
            assertThat(breakdown).extracting(UsageCostBreakdown.Bucket::key)
                .containsExactly(
                    "anthropic/claude-fable-5-20261001",  // responseModel 优先，cost 最高
                    "anthropic/claude-fable-5",
                    "Tools/summaries");
            assertThat(breakdown.get(2).cost()).isEqualTo(0.0007,
                org.assertj.core.api.Assertions.within(1e-9)); // 5+20 的 toolResult+summary 合桶
        }
    }

    @Test
    void breakdownFiltersZeroBuckets() throws Exception {
        try (var session = session()) {
            session.appendUsage("cache_warm", "a", "m",
                usage(0, 0, 0, 0, 0), null);   // 零桶
            session.appendUsage("cache_warm", "b", "m",
                usage(10, 0, 0, 0, 0), null);  // tokens>0 保留
            var breakdown = UsageCostBreakdown.of(session.accumulatedEntries());
            assertThat(breakdown).extracting(UsageCostBreakdown.Bucket::key)
                .containsExactly("b/m");
        }
    }

    @Test
    void sessionCommandFollowsPiSectionOrder() throws Exception {
        try (var session = session()) {
            session.appendUsage("cache_warm", "anthropic", "claude-fable-5",
                usage(1_000, 40, 500, 100, 0.01), null);
            var result = session.services().slashCommands()
                .dispatch("/session", SlashContext.of(session))
                .toCompletableFuture().get(10, TimeUnit.SECONDS);

            // 段序钉死（pi :6708-6767）：Info → Messages → Tokens → Cache Warming → Cost。
            assertThat(result).containsSubsequence(
                "Session Info", "Messages", "Tokens", "Cache Warming", "Cost");
            assertThat(result).contains("ID: " + session.sessionId());
            assertThat(result).contains("Input: 1,600");          // 1000+500+100 千分位
            assertThat(result).contains("  Cached: 500 (31.3%)"); // 500/1600 命中率
            assertThat(result).contains("  Uncached: 1,100 (100 written to cache)");
            assertThat(result).contains("Total: $0.010");
            // 单桶非当前模型键 ⇒ 列 breakdown 行（当前模型是缺省解析结果，非 anthropic/…）。
            assertThat(result).contains("anthropic/claude-fable-5: $0.010 (1.6k tokens)");
        }
    }

    @Test
    void sessionCommandEmptyOmitsCacheSplitAndCost() throws Exception {
        try (var session = session()) {
            var result = session.services().slashCommands()
                .dispatch("/session", SlashContext.of(session))
                .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertThat(result).contains("Tokens\nInput: 0\nOutput: 0\nTotal: 0");
            assertThat(result).doesNotContain("Cached:");
            assertThat(result).doesNotContain("Cost");
            assertThat(result).contains("Cache Warming\nMode:");
        }
    }
}
