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
        // 沿 parentId 链；keep=1 ⇒ cut 落在最后一条 assistant，前三条进摘要。
        var transcript = chained(
                message("user", "first"),
                message("assistant", "second"),
                message("user", "third"),
                message("assistant", "fourth"));
        var settings = new CompactionSettings(true, 16384, 1);
        var result = CompactionService.compact(transcript, settings,
            com.pijava.agent.compaction.SummaryGenerator.truncating(), 42L);

        assertThat(result.summary()).isNotBlank();
        assertThat(result.firstKeptEntryId()).isEqualTo(transcript.get(3).id());
        assertThat(result.tokensBefore()).isEqualTo(42);
    }

    @Test
    void compactThrowsWhenNothingSummarizable_piAnchor() {
        // pi 锚点 200387122 的 prepareCompaction：空路径 ⇒ undefined；单条小消息
        // 切点只能落在它自己身上、其前没有可摘要消息 ⇒ 同样 undefined
        // （旧「单条可压」的判断来自更早的分析，在现锚点不成立）。
        var single = List.of(message("user", "only"));
        assertThatThrownBy(() -> CompactionService.compact(single,
            CompactionSettings.defaults(),
            com.pijava.agent.compaction.SummaryGenerator.truncating(), 7L))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> CompactionService.compact(
                List.of(), CompactionSettings.defaults(),
                com.pijava.agent.compaction.SummaryGenerator.truncating(), 7L))
                .isInstanceOf(IllegalStateException.class);
    }

    /** 消息按序串 parentId 链。 */
    private static List<Entry> chained(Entry... entries) {
        List<Entry> out = new java.util.ArrayList<>();
        String parent = null;
        for (Entry entry : entries) {
            var source = (Entry.Message) entry;
            var copy = new Entry.Message(source.id(), 0, parent,
                source.timestamp(), source.message(), source.terminate());
            out.add(copy);
            parent = source.id();
        }
        return List.copyOf(out);
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

        h.compact(new CompactionSettings(true, 16384, 1));
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
