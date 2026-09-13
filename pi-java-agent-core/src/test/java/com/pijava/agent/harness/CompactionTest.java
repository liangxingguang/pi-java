package com.pijava.agent.harness;

import java.util.List;
import java.util.Set;

import com.pijava.agent.compaction.CompactionResult;
import com.pijava.agent.compaction.CompactionService;
import com.pijava.agent.compaction.CompactionSettings;
import com.pijava.agent.entry.Entry;
import com.pijava.agent.hook.CompactionPlan;
import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ModelThinkingLevel;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompactionTest {

    private static final ModelId<?> MODEL = ModelId.of("faux", "test-model");

    private static AgentHarness harness() {
        var partial = AssistantMessage.empty()
                .withContent(List.of(new ContentBlock.TextContent("ok")))
                .withStopReason("stop");
        StreamFn sf = (model, context, options) -> StreamIterator.from(List.of(
                new StreamEvent.Start(AssistantMessage.empty()),
                new StreamEvent.TextEnd(0, "ok", partial),
                new StreamEvent.StreamDone("stop", null, partial)));
        return AgentHarness.create(new HarnessConfig(
                sf, MODEL, ModelThinkingLevel.off(), "",
                Set.of(), 200_000, null, null, null,
            null, java.util.Map.of(),
                com.pijava.ai.http.RetryPolicy.defaultPolicy(),
                com.pijava.telemetry.NoopTelemetryContext.INSTANCE, com.pijava.ai.thinking.ThinkingLevelMap.empty(),
                QueueMode.defaultMode(), QueueMode.defaultMode(), ToolExecution.defaultMode(),
                event -> { }));
    }

    private static Entry message(String role, String text) {
        Message message = "assistant".equals(role)
            ? new Message.AssistantMessage(List.of(new ContentBlock.TextContent(text)))
            : new Message.UserMessage(List.of(new ContentBlock.TextContent(text)));
        return new Entry.Message(java.util.UUID.randomUUID().toString(), 0, null, null,
            message, null);
    }

    // ── CompactionService unit tests ──────────────────────────

    @Test
    void compactReducesTranscriptToRetentionRatio() {
        var transcript = List.of(
                message("user", "first"),
                message("assistant", "second"),
                message("user", "third"),
                message("assistant", "fourth"));
        var settings = new CompactionSettings(true, 16384, 20);
        var result = CompactionService.compact(transcript, settings,
            com.pijava.agent.compaction.SummaryGenerator.truncating(), 42L);

        assertThat(result.summary()).isNotBlank();
        // Small transcript: the fallback cut keeps only the last message.
        // 新估算（pi 的 ceil(chars/4)，:278）四条累加 2+2+2+2=8 < 20 ⇒
        // 仍走兜底切点；keep=8 在 3b 后会在第 0 条就达阈（ceil 更大），
        // 那是 findCutPoint 的正常路，不是兜底路。
        assertThat(result.firstKeptEntryId()).isEqualTo(transcript.get(3).id());
        assertThat(result.tokensBefore()).isEqualTo(42);
    }

    @Test
    void compactThrowsOnlyOnEmptyTranscript_pi638() {
        // pi 的 prepareCompaction 只在**空路径**时不可压缩（compaction.ts:638）；
        // 单条消息**可压** —— 切点落在它自己身上（findCutPoint :389 的
        // cutPoints[0]）。旧实现把 size<=1 全判为不可压，是发明，3b 撤下。
        var single = List.of(message("user", "only"));
        var result = CompactionService.compact(single, CompactionSettings.defaults(),
            com.pijava.agent.compaction.SummaryGenerator.truncating(), 7L);
        assertThat(result.firstKeptEntryId()).isEqualTo(single.get(0).id());
        assertThat(result.tokensBefore()).isEqualTo(7);
        assertThatThrownBy(() -> CompactionService.compact(
                List.of(), CompactionSettings.defaults(),
                com.pijava.agent.compaction.SummaryGenerator.truncating(), 7L))
                .isInstanceOf(IllegalStateException.class);
    }

    // ── Harness compaction tests ──────────────────────────────

    @Test
    void harnessCompactThrowsNothingToCompactOnEmptyLane() {
        var h = harness();
        assertThatThrownBy(() -> h.compact(CompactionSettings.defaults()))
                .isInstanceOf(NothingToCompactException.class);
    }

    @Test
    void harnessCompactReducesPopulatedLane() {
        var h = harness();
        h.prompt("hello");
        int before = h.snapshot("default").transcript().size();
        assertThat(before).isGreaterThan(1);

        h.compact(new CompactionSettings(true, 16384, 20000));
        int after = h.snapshot("default").transcript().size();
        assertThat(after).isLessThanOrEqualTo(before);
    }

    @Test
    void beforeCompactionHookCanOverridePlan() {
        var h = harness();
        h.prompt("hello");

        var keep = message("assistant", "kept by hook");
        h.hookSystem().onBeforeCompaction("default",
                ctx -> new CompactionPlan(List.of(keep), 10));
        h.compact(CompactionSettings.defaults());

        var transcript = h.snapshot("default").transcript();
        assertThat(transcript).containsExactly(keep);
    }
}
