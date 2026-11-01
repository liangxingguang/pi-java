package com.pijava.coding.agent.core;

import java.util.List;

import com.pijava.agent.harness.Context;
import com.pijava.agent.harness.StreamFn;
import com.pijava.agent.harness.StreamOptions;
import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Cache-warmer error synthesis retains response metadata already present on partial. */
class SessionWiringResponseIdTest {

    @Test
    void warmStreamErrorPreservesPartialResponseId() {
        AssistantMessage base = AssistantMessage.empty().withStopReason(StopReason.ERROR);
        AssistantMessage partial = new AssistantMessage(base.id(), base.content(), base.usage(),
                base.stopReason(), base.api(), base.provider(), base.model(), base.responseModel(),
                base.providerThinkingLevel(), base.diagnostics(), base.timestamp(),
                base.errorMessage(), base.rawStopReason(), base.durationMs(), "response-warm");
        StreamFn fn = (model, context, options) -> StreamIterator.from(List.of(
                new StreamEvent.StreamError(StopReason.ERROR, new RuntimeException("boom"), partial)));

        var result = SessionWiring.warmStream(fn).streamToFinal(ModelId.of("faux", "faux"),
                Context.of(List.of()), StreamOptions.defaults(), new CacheWarmer.WarmAbort() {
                    @Override public void abort() { }
                    @Override public void bind(AutoCloseable inFlight) { }
                    @Override public boolean aborted() { return false; }
                });

        assertThat(result.responseId()).isEqualTo("response-warm");
    }
}
