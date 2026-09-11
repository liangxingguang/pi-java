package com.pijava.coding.agent.core;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.pijava.agent.record.LaneRecord;
import com.pijava.agent.record.StepKind;
import com.pijava.coding.agent.core.RunSummaryAggregator.Summary;
import com.pijava.coding.agent.core.RunSummaryAggregator.Totals;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RunSummaryAggregator} 纯聚合逻辑（observability design §8）：
 * 跨 attempt 的 runId 过滤、工具成败分流、usage 累计、格式化。
 */
class RunSummaryAggregatorTest {

    private static final Instant NOW = Instant.parse("2026-09-10T00:00:00Z");

    private static LaneRecord.ToolFinished tool(String runId, boolean isError) {
        return new LaneRecord.ToolFinished(
            UUID.randomUUID().toString(), 0, "lane", NOW, runId,
            UUID.randomUUID().toString(), "shell", isError, false,
            UUID.randomUUID().toString(), 100L);
    }

    private static LaneRecord.StepAttempt step(String runId) {
        return new LaneRecord.StepAttempt(
            UUID.randomUUID().toString(), 0, "lane", NOW, runId,
            StepKind.ASSISTANT, 0, UUID.randomUUID().toString(), null,
            "model", 1, 0, null, 100L);
    }

    @Test
    void aggregatesOnlyRecordsForThisDriveRunIds() {
        String runA = "run-a";
        String runB = "run-b";
        var records = List.<LaneRecord>of(
            tool(runA, false),
            tool(runA, false),
            tool(runA, true),
            tool(runB, false),          // other run (stale attempt), must not count
            step(runA),
            step(runA),
            step(runB));                // other run, must not count

        Summary summary = RunSummaryAggregator.aggregate(records, Set.of(runA));

        assertThat(summary.toolOk()).isEqualTo(2);
        assertThat(summary.toolFailed()).isEqualTo(1);
        assertThat(summary.steps()).isEqualTo(2);
    }

    @Test
    void aggregatesAcrossAllAttemptRunIds() {
        String runA = "run-a";
        String runB = "run-b";
        var records = List.<LaneRecord>of(
            tool(runA, false),
            tool(runB, true),
            step(runA),
            step(runB));

        Summary summary = RunSummaryAggregator.aggregate(records, Set.of(runA, runB));

        assertThat(summary.toolOk()).isEqualTo(1);
        assertThat(summary.toolFailed()).isEqualTo(1);
        assertThat(summary.steps()).isEqualTo(2);
    }

    @Test
    void emptyRecordsYieldZeroedCounters() {
        Summary summary = RunSummaryAggregator.aggregate(List.of(), Set.of("run-a"));
        assertThat(summary.toolOk()).isZero();
        assertThat(summary.toolFailed()).isZero();
        assertThat(summary.steps()).isZero();
    }

    @Test
    void withMetaAndWithTotalsCopyPreserveCounters() {
        Summary base = RunSummaryAggregator.aggregate(
            List.of(tool("run-a", false), step("run-a")), Set.of("run-a"));
        Summary meta = base.withMeta(2, 5_234L, "completed");
        Summary full = meta.withTotals(new Totals(12_340, 1_021, 0.0432));

        assertThat(full.attempts()).isEqualTo(2);
        assertThat(full.durationMs()).isEqualTo(5_234L);
        assertThat(full.stopReason()).isEqualTo("completed");
        assertThat(full.totals().inputTokens()).isEqualTo(12_340);
        assertThat(full.totals().outputTokens()).isEqualTo(1_021);
        assertThat(full.totals().costUsd()).isEqualTo(0.0432);
        assertThat(full.toolOk()).isEqualTo(1);
        assertThat(full.steps()).isEqualTo(1);
    }

    @Test
    void printToFormatsThousandsGroupedAndCurrency() {
        var out = new ByteArrayOutputStream();
        new Summary(1, 5_234L, "completed",
            new Totals(12_340, 1_021, 0.0432), 6, 1, 3)
            .printTo(new PrintStream(out, true, StandardCharsets.UTF_8));

        var text = out.toString(StandardCharsets.UTF_8);
        assertThat(text).contains(
            "[pi-java] run summary: attempts=1 durationMs=5234ms stopReason=completed");
        assertThat(text).contains("tokens: in=12,340 out=1,021 cost=$0.04");
        assertThat(text).contains("tools: 6 ok, 1 failed | steps: 3");
    }
}
