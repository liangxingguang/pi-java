package com.pijava.agent.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ModelThinkingLevel;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * pi 对齐：连续 prompt 复用既有 transcript（agent-loop.ts
 * {@code messages: [...context.messages, ...prompts]}），只有 reset 清空。
 */
class CrossTurnContextTest {

    private static final ModelId<?> MODEL = ModelId.of("faux", "test-model");

    private static AgentHarness harness(AtomicReference<List<Message>> captured) {
        var partial = AssistantMessage.empty()
            .withContent(List.of(new ContentBlock.TextContent("done")))
            .withStopReason(StopReason.STOP);
        StreamFn sf = (model, context, options) -> {
            captured.set(List.copyOf(context.messages()));
            return StreamIterator.from(List.of(
                new StreamEvent.Start(AssistantMessage.empty()),
                new StreamEvent.TextEnd(0, "done", partial),
                new StreamEvent.StreamDone(StopReason.STOP, null, partial)));
        };
        return AgentHarness.create(new HarnessConfig(
            sf, MODEL, ModelThinkingLevel.off(), "",
            Set.of(), 200_000, null, null, null,
            null, java.util.Map.of(),
            com.pijava.telemetry.NoopTelemetryContext.INSTANCE,
            com.pijava.ai.thinking.ThinkingLevelMap.empty(),
            QueueMode.defaultMode(), QueueMode.defaultMode(), ToolExecution.defaultMode(),
            event -> { }));
    }

    @Test
    void secondRunSeesFirstTurnMessages() {
        var captured = new AtomicReference<List<Message>>();
        var h = harness(captured);

        h.prompt("turn one");
        List<Message> firstCall = captured.get();

        h.prompt("turn two");
        List<Message> secondCall = captured.get();

        assertThat(firstCall).hasSize(1);
        // pi alignment: second request = prior context + new prompt
        assertThat(secondCall).hasSize(firstCall.size() + 2);
        List<String> texts = secondCall.stream()
            .flatMap(m -> m.content().stream())
            .filter(ContentBlock.TextContent.class::isInstance)
            .map(b -> ((ContentBlock.TextContent) b).text())
            .toList();
        assertThat(texts).contains("turn one", "turn two");
    }

    @Test
    void resetStillClearsContext() {
        var captured = new AtomicReference<List<Message>>();
        var h = harness(captured);
        h.prompt("before reset");

        h.reset("default");
        h.prompt("after reset");

        assertThat(captured.get()).hasSize(1);
        List<String> texts = captured.get().stream()
            .flatMap(m -> m.content().stream())
            .filter(ContentBlock.TextContent.class::isInstance)
            .map(b -> ((ContentBlock.TextContent) b).text())
            .toList();
        assertThat(texts).containsExactly("after reset");
    }

    /**
     * The ContextEntries projection reaches the provider context through the
     * <em>resume seed</em> path too, not just through the live transcript.
     * <b>#58 翻转</b>：现行 pi 主流不过滤 stopReason（{@code agent-loop.ts:441-450}
     * 写回、{@code agent.ts:38-46} 只按 role），deferred/error/aborted 的 seeded
     * assistant 条目同样原样发给 provider。
     */
    @Test
    void seededErrorOrAbortedAssistantEntryReachesTheProviderContext() {
        for (StopReason stopReason : List.of(StopReason.DEFERRED, StopReason.ERROR, StopReason.ABORTED)) {
            var captured = new AtomicReference<List<Message>>();
            var h = harness(captured);

            h.seedTranscript("default", List.of(
                new Entry.Message("u-1", 0, null, null,
                    new Message.UserMessage(List.of(new ContentBlock.TextContent("hello"))), null),
                new Entry.Message("a-1", 0, "u-1", null,
                    new Message.AssistantMessage(
                        List.of(new ContentBlock.TextContent("partial text")), stopReason, null),
                    null)));

            h.prompt("next");

            var texts = captured.get().stream()
                .flatMap(m -> m.content().stream())
                .filter(ContentBlock.TextContent.class::isInstance)
                .map(b -> ((ContentBlock.TextContent) b).text())
                .toList();
            assertThat(texts)
                .as("stopReason=%s 的 seeded assistant 条目投影后仍在列（#58）", stopReason)
                .contains("hello", "partial text", "next");
        }
    }

    @Test
    void transcriptGrowsAcrossRuns() {
        var captured = new AtomicReference<List<Message>>();
        var h = harness(captured);
        h.prompt("a");
        int afterFirst = h.snapshot("default").transcript().size();

        h.prompt("b");

        var entries = h.snapshot("default").transcript();
        assertThat(entries.size()).isGreaterThan(afterFirst);
        assertThat(new ArrayList<>(entries).stream()
            .filter(Entry.Message.class::isInstance)
            .map(e -> (Entry.Message) e)
            .anyMatch(m -> "user".equals(m.message().role()))).isTrue();
    }
}
