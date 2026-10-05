package com.pijava.agent.session;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.entry.ProvisionedEntry;
import com.pijava.agent.harness.AgentHarness;
import com.pijava.agent.harness.HarnessConfig;
import com.pijava.agent.harness.QueueMode;
import com.pijava.agent.harness.ToolExecution;
import com.pijava.agent.session.jsonl.JsonlSessionCreateOptions;
import com.pijava.agent.session.jsonl.JsonlSessionRepository;
import com.pijava.ai.Usage;
import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ModelThinkingLevel;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * B168（{@code docs/16}）：usage 提为一等 entry 后，「按 runId 关联」的读法。
 *
 * <p>pi 锚点 {@code 200387122} 生产代码里没有 {@code runId}（仅 evals 有），
 * pi 消费 usage 的形状是按 {@code type==="usage"} 扫描 entry 流；本仓在扫描
 * 读到的行上保留 runId 审计键。本文件钉两条：生产者确实把 runId 写进了
 * usage 条目；type 扫描查询能把它带回来。</p>
 */
class RunIdUsageQueryTest {

    private static final ModelId<?> MODEL = ModelId.of("faux", "test-model");

    private static AgentHarness harnessWith(com.pijava.agent.harness.StreamFn sf) {
        return AgentHarness.create(new HarnessConfig(
            sf, MODEL, ModelThinkingLevel.off(), "",
            Set.of(), 200_000, null, null, null,
            null, java.util.Map.of(),
            com.pijava.telemetry.NoopTelemetryContext.INSTANCE,
            com.pijava.ai.thinking.ThinkingLevelMap.empty(),
            QueueMode.defaultMode(), QueueMode.defaultMode(), ToolExecution.defaultMode(),
            event -> { }));
    }

    /** 生产者侧：驱动一轮，落出的 usage 条目必须带着所属 run 的 id。 */
    @Test
    void producedUsageEntryCarriesTheRunId() {
        // Stream 事件携带的是顶层 partial（id/content/usageInfo/stopReason…），
        // harness 再从它投影出终局 Message.AssistantMessage。
        var finalPartial = new AssistantMessage(
            "msg-1",
            List.of(new ContentBlock.TextContent("ok")),
            new StreamEvent.UsageInfo(100, 20, null,
                new Usage(100, 20, 0, 0, null, null, 120, Usage.Cost.zero())),
            "stop", null, "faux", "test-model", null, null, null);
        var h = harnessWith((model, context, options) -> StreamIterator.from(List.of(
            new StreamEvent.Start(AssistantMessage.empty()),
            new StreamEvent.StreamDone("stop", null, finalPartial))));

        var outcome = h.prompt("drive one turn");

        // pi 的消费形状：扫描 transcript，按 type 取 usage。
        var usages = outcome.transcript().stream()
            .filter(Entry.Usage.class::isInstance)
            .map(Entry.Usage.class::cast)
            .toList();
        assertThat(usages).hasSize(1);
        assertThat(usages.getFirst().runId())
            .as("runId 是关联「这次 run 的计量」的唯一审计键，生产者不许漏写")
            .isEqualTo(outcome.runId());
        assertThat(usages.getFirst().usage().input())
            .as("usage 载荷也要在（type 扫描读到的是真记账行，不是空壳）")
            .isEqualTo(100);
    }

    /** 查询侧：type 扫描（pi 的读法）返回 usage 行，行上的 runId 原样可读。 */
    @Test
    void typeScanQueryReturnsUsageRowsWithRunId() throws Exception {
        Path dir = Files.createTempDirectory("runid-query");
        var repo = JsonlSessionRepository.over(dir);
        var session = repo.create(new JsonlSessionCreateOptions(null, "work", null, null));
        session.appendEntry(new ProvisionedEntry<>(new Entry.Usage(
            "u1", 0, null, null, "assistant", "faux", "faux-model",
            Usage.of(5, 2), null, "run-1", "e1", null, 0, "stop")), "main");
        session.storage().drain();

        var usages = session.findEntries(
            new EntryQuery(Entry.TYPE_USAGE, null, EntryOrder.OLDEST_FIRST, null, null));
        assertThat(usages).hasSize(1);
        assertThat(((Entry.Usage) usages.getFirst()).runId()).isEqualTo("run-1");

        // type 过滤的反侧：同一查询面取 message 不该混进 usage 行。
        var messages = session.findEntries(
            new EntryQuery(Entry.TYPE_MESSAGE, null, EntryOrder.OLDEST_FIRST, null, null));
        assertThat(messages).isEmpty();
    }
}
