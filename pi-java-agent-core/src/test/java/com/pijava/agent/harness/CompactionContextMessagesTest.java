package com.pijava.agent.harness;

import java.util.List;
import java.util.Set;

import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ModelThinkingLevel;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live compaction must surface the summary in LLM context (pi
 * {@code buildSessionContext}): after {@code compact()}, the built messages
 * contain the summary user message followed by the kept tail.
 */
class CompactionContextMessagesTest {

    private static final ModelId<?> MODEL = ModelId.of("faux", "test-model");

    private static AgentHarness harness() {
        var partial = AssistantMessage.empty()
                .withContent(List.of(new ContentBlock.TextContent("ok")))
                .withStopReason("stop");
        StreamFn sf = (messages, model, options) -> StreamIterator.from(List.of(
                new StreamEvent.Start(AssistantMessage.empty()),
                new StreamEvent.TextEnd(0, "ok", partial),
                new StreamEvent.StreamDone("stop", null, partial)));
        return AgentHarness.create(new HarnessConfig(
                sf, MODEL, ModelThinkingLevel.off(), "",
                Set.of(), 200_000, null, null, null,
                DriveMode.MANUAL, null, java.util.Map.of(),
                com.pijava.ai.http.RetryPolicy.defaultPolicy(),
                com.pijava.telemetry.NoopTelemetryContext.INSTANCE,
                com.pijava.ai.thinking.ThinkingLevelMap.empty(),
                QueueMode.defaultMode(), QueueMode.defaultMode(), ToolExecution.defaultMode(),
                event -> { }));
    }

    private static String textOf(Message m) {
        if (m instanceof Message.UserMessage u) {
            return ((ContentBlock.TextContent) u.content().get(0)).text();
        }
        return null;
    }

    @Test
    void afterCompactSummaryEntersContext() {
        var harness = harness();
        var lane = AgentHarness.DEFAULT_LANE;
        var user1 = new com.pijava.agent.entry.Entry.Message("u1", 0, null, null,
            new Message.UserMessage(List.of(new ContentBlock.TextContent("q1"))), null);
        var user2 = new com.pijava.agent.entry.Entry.Message("u2", 0, "u1", null,
            new Message.UserMessage(List.of(new ContentBlock.TextContent("q2"))), null);
        harness.seedTranscript(lane, List.of(user1, user2));
        harness.compact(lane, com.pijava.agent.compaction.CompactionSettings.defaults());

        var built = buildContext(harness, lane);
        // First non-system message carries the pi-exact summary prefix
        var first = built.stream()
            .filter(m -> m instanceof Message.UserMessage)
            .findFirst().orElseThrow();
        assertThat(textOf(first)).startsWith(
            "The conversation history before this point was compacted into the following summary:");
        // The kept tail (u2) still appears in context
        assertThat(built).anyMatch(m -> m == user2.message());
    }

    private static List<Message> buildContext(AgentHarness harness, String lane) {
        // buildMessagesForLane is package-private; exercise via snapshot of the
        // transcript through the same code path used for the LLM request.
        var transcript = harness.snapshot(lane).transcript();
        return com.pijava.agent.session.ContextEntries.toMessages(
            com.pijava.agent.session.ContextEntries.pathToLeaf(transcript,
                transcript.isEmpty() ? null : transcript.get(transcript.size() - 1).id()));
    }
}
