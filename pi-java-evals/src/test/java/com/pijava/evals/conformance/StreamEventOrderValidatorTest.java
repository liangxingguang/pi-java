package com.pijava.evals.conformance;

import java.util.List;

import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.stream.StreamEvent;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Direct checks for {@link StreamEventOrderValidator}.
 */
class StreamEventOrderValidatorTest {

    @Test
    void validTextStreamHasNoProblems() {
        var events = collect(ConformanceFixtures.text());
        assertThat(StreamEventOrderValidator.problems(events)).isEmpty();
        StreamEventOrderValidator.assertValid(events);
    }

    @Test
    void emptyStreamIsInvalid() {
        assertThat(StreamEventOrderValidator.problems(List.of()))
            .contains("stream is empty");
        assertThatThrownBy(() -> StreamEventOrderValidator.assertValid(List.of()))
            .isInstanceOf(AssertionError.class);
    }

    @Test
    void missingStartIsInvalid() {
        var empty = AssistantMessage.empty();
        var events = List.<StreamEvent>of(new StreamEvent.StreamDone("stop", null, empty));
        assertThat(StreamEventOrderValidator.problems(events))
            .anyMatch(p -> p.contains("Start"));
    }

    private static java.util.ArrayList<StreamEvent> collect(
            com.pijava.ai.provider.FauxProvider provider) {
        var api = provider.createApi(
            com.pijava.ai.api.ChatApi.class, com.pijava.ai.api.ApiOptions.defaults());
        var request = com.pijava.ai.api.StreamRequest.of(
            com.pijava.ai.model.ModelId.of("faux", "t"),
            java.util.List.of(new com.pijava.ai.message.Message.UserMessage(
                java.util.List.of(new com.pijava.ai.message.ContentBlock.TextContent("x")))));
        var events = new java.util.ArrayList<StreamEvent>();
        try (var iterator = api.streamBlocking(request, com.pijava.ai.api.ApiOptions.defaults())) {
            while (iterator.hasNext()) {
                events.add(iterator.next());
            }
        }
        return events;
    }
}
