package com.pijava.agent.harness;

import java.util.List;
import java.util.Set;

import com.pijava.agent.compaction.CompactionSettings;
import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ModelThinkingLevel;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration: two harnesses（会话分支的两个宿主）+ hook + compaction.
 *
 * <p>此前这里跑的是「一个 harness 两条车道」；多车道运行时容器删除后
 * （{@code docs/31 §4.3}），两条并行上下文就是**两个 harness**——父会话一个、
 * 分支会话一个（{@link AgentHarness#fork()}）。</p>
 */
class AgentHarnessIntegrationTest {

    private static final ModelId<?> MODEL = ModelId.of("faux", "test-model");

    private static StreamFn streamFn(String reply) {
        var partial = AssistantMessage.empty()
                .withContent(List.of(new ContentBlock.TextContent(reply)))
                .withStopReason("stop");
        return (model, context, options) -> StreamIterator.from(List.of(
                new StreamEvent.Start(AssistantMessage.empty()),
                new StreamEvent.TextEnd(0, reply, partial),
                new StreamEvent.StreamDone("stop", null, partial)));
    }

    private static AgentHarness harness() {
        return AgentHarness.create(new HarnessConfig(
                streamFn("assistant reply"), MODEL, ModelThinkingLevel.off(), "",
                Set.of(), 200_000, null, null, null,
            null, java.util.Map.of(),
                com.pijava.ai.http.RetryPolicy.defaultPolicy(),
                com.pijava.telemetry.NoopTelemetryContext.INSTANCE, com.pijava.ai.thinking.ThinkingLevelMap.empty(),
                QueueMode.defaultMode(), QueueMode.defaultMode(), ToolExecution.defaultMode(),
                event -> { }));
    }

    @Test
    void forkedHarnessRunsIndependentlyWithHookAndCompaction() {
        var h = harness();
        var forked = h.fork();

        // Register a hook that fires on every run end — only on the parent.
        var runEndCount = new int[1];
        h.hookSystem().onBeforeRunEnd("default", ctx -> runEndCount[0]++);

        // Two parallel contexts: one per harness.
        h.prompt("review this code", List.of());
        forked.prompt("edit this file", List.of());

        assertThat(runEndCount[0]).as("hook 只注册在父 harness 上").isEqualTo(1);
        assertThat(h.snapshot("default").transcript()).isNotEmpty();
        assertThat(forked.snapshot("default").transcript()).isNotEmpty();
        assertThat(forked.snapshot("default").transcript())
            .as("分支 harness 有自己的日志").isNotEqualTo(h.snapshot("default").transcript());

        // Compaction on the parent after more turns
        h.prompt("one more turn", List.of());
        var before = h.snapshot("default").transcript().size();
        h.compact(new CompactionSettings(true, 16384, 20000));
        var after = h.snapshot("default").transcript().size();
        assertThat(after).isLessThanOrEqualTo(before);
    }

    @Test
    void watchReceivesSnapshotOnStateChange() {
        var h = harness();
        var updates = new int[1];
        var handle = h.watch("default");
        handle.subscribe(snapshot -> updates[0]++);

        h.prompt("default", "hello", List.of());
        assertThat(updates[0]).isGreaterThan(0);
    }
}
