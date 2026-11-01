package com.pijava.agent.compaction;

import java.util.List;
import java.util.Map;

import com.pijava.agent.harness.RetryObserver;
import com.pijava.agent.harness.RetrySettings;
import com.pijava.agent.harness.StreamFn;
import com.pijava.ai.Usage;
import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Summary terminal reconstruction must not clear B264 metadata. */
class B264SummaryMetadataTest {

    private static final ModelId<?> MODEL = ModelId.of("faux", "summary");

    @Test
    void terminalUsageBackfillPreservesResponseId() {
        AssistantMessage done = partial("response-backfill", StopReason.STOP);
        StreamFn fn = (model, context, options) -> StreamIterator.from(List.of(
                new StreamEvent.UsageInfo(3, 2, done, Usage.of(3, 2)),
                new StreamEvent.StreamDone(StopReason.STOP, null, done)));

        var result = new LlmSummaryGenerator(fn, () -> MODEL).callBranch("prompt", 16, "manual");
        assertThat(result.responseId()).isEqualTo("response-backfill");
    }

    @Test
    void collectedToolCallPreservesArgumentsError() {
        AssistantMessage empty = AssistantMessage.empty();
        StreamFn fn = (model, context, options) -> StreamIterator.from(List.of(
                new StreamEvent.ToolCallEnd(0, "call-1", "read", Map.of(),
                        "ns", "bad-json", empty),
                new StreamEvent.StreamDone(StopReason.TOOL_USE, null, empty)));

        var result = new LlmSummaryGenerator(fn, () -> MODEL).callBranch("prompt", 16, "manual");
        ContentBlock.ToolUseContent call = (ContentBlock.ToolUseContent) result.content().getFirst();
        assertThat(call.argumentsError()).isEqualTo("bad-json");
    }

    @Test
    void abortNormalizationPreservesResponseId() {
        AssistantMessage error = partial("response-abort", StopReason.ERROR);
        StreamFn fn = (model, context, options) -> StreamIterator.from(List.of(
                new StreamEvent.StreamError(StopReason.ERROR, new RuntimeException("overloaded"), error)));
        var generator = new LlmSummaryGenerator(fn, () -> MODEL,
                () -> new RetrySettings(true, 3, 1, 1_000L), () -> true, RetryObserver.NOOP);

        var result = generator.callBranch("prompt", 16, "manual");
        assertThat(result.stopReason()).isEqualTo(StopReason.ABORTED);
        assertThat(result.responseId()).isEqualTo("response-abort");
    }

    private static AssistantMessage partial(String responseId, StopReason reason) {
        AssistantMessage base = AssistantMessage.empty().withStopReason(reason);
        return new AssistantMessage(base.id(), base.content(), base.usage(), base.stopReason(),
                base.api(), base.provider(), base.model(), base.responseModel(),
                base.providerThinkingLevel(), base.diagnostics(), base.timestamp(),
                base.errorMessage(), base.rawStopReason(), base.durationMs(), responseId);
    }

}
