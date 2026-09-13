package com.pijava.agent.harness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.agent.record.LaneRecord;
import com.pijava.telemetry.JsonlFileTelemetry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Observability wiring: a real harness run with {@link JsonlFileTelemetry}
 * emits harness.run / llm.request spans, counters and timings into the trace
 * file, and StepAttempt records carry the LLM request summary + duration.
 */
class HarnessTelemetrySpansTest {

    private static final ModelId<?> MODEL = ModelId.of("faux", "test-model");
    private final ObjectMapper mapper = new ObjectMapper();

    private static StreamFn streamFn(String reply) {
        var partial = AssistantMessage.empty()
                .withContent(List.of(new ContentBlock.TextContent(reply)))
                .withStopReason("tool_use");
        return (model, context, options) -> StreamIterator.from(List.of(
                new StreamEvent.Start(AssistantMessage.empty()),
                new StreamEvent.TextEnd(0, reply, partial),
                new StreamEvent.StreamDone("tool_use", null, partial)));
    }

    private List<JsonNode> readLines(Path tracesDir) throws IOException {
        try (var stream = Files.list(tracesDir)) {
            var file = stream.filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                .findFirst().orElseThrow();
            var lines = new ArrayList<JsonNode>();
            for (String line : Files.readAllLines(file)) {
                if (!line.isBlank()) {
                    lines.add(mapper.readTree(line));
                }
            }
            return lines;
        }
    }

    @Test
    void runEmitsRunAndLlmSpansWithAttributes(@TempDir Path tracesDir) throws IOException {
        var telemetry = JsonlFileTelemetry.create(tracesDir);
        var h = AgentHarness.create(new HarnessConfig(
                streamFn("assistant reply"), MODEL, ModelThinkingLevel.off(), "",
                Set.of(), 200_000, null, null, null,
            null, java.util.Map.of(),
                com.pijava.ai.http.RetryPolicy.defaultPolicy(),
                telemetry, com.pijava.ai.thinking.ThinkingLevelMap.empty(),
                QueueMode.defaultMode(), QueueMode.defaultMode(), ToolExecution.defaultMode(),
                event -> { }));

        h.prompt("hello");

        var lines = readLines(tracesDir);
        var runStarts = lines.stream()
            .filter(n -> "span_start".equals(n.get("kind").asText())
                && "harness.run".equals(n.get("name").asText()))
            .toList();
        var runEnds = lines.stream()
            .filter(n -> "span_end".equals(n.get("kind").asText()))
            .filter(n -> {
                var id = n.get("spanId").asText();
                return runStarts.stream().anyMatch(s -> s.get("spanId").asText().equals(id));
            })
            .toList();
        var llmStarts = lines.stream()
            .filter(n -> "span_start".equals(n.get("kind").asText())
                && "llm.request".equals(n.get("name").asText()))
            .toList();

        assertThat(runStarts).hasSize(1);
        assertThat(runEnds).hasSize(1);
        assertThat(llmStarts).hasSize(1);

        // llm.request nests under harness.run
        assertThat(llmStarts.get(0).get("parentSpanId").asText())
            .isEqualTo(runStarts.get(0).get("spanId").asText());

        // run end carries outcome + promptChars; llm end carries model + stopReason
        assertThat(runEnds.get(0).get("attrs").get("outcome").asText()).isEqualTo("completed");
        assertThat(runEnds.get(0).get("attrs").get("promptChars").asInt()).isEqualTo(5);
        var llmEndSpanId = llmStarts.get(0).get("spanId").asText();
        var llmEnd = lines.stream()
            .filter(n -> "span_end".equals(n.get("kind").asText())
                && n.get("spanId").asText().equals(llmEndSpanId))
            .findFirst().orElseThrow();
        assertThat(llmEnd.get("attrs").get("model").asText()).isEqualTo("faux/test-model");
        assertThat(llmEnd.get("attrs").get("stopReason").asText()).isEqualTo("tool_use");
        assertThat(llmEnd.get("durationMs").asLong()).isGreaterThanOrEqualTo(0);

        // counters + timing lines
        assertThat(lines.stream().anyMatch(n -> "counter".equals(n.get("kind").asText())
            && "llm.requests".equals(n.get("name").asText()))).isTrue();
        assertThat(lines.stream().anyMatch(n -> "timing".equals(n.get("kind").asText())
            && "llm.request.duration".equals(n.get("name").asText()))).isTrue();
    }

    @Test
    void stepAttemptRecordCarriesSummaryAndDuration(@TempDir Path tracesDir) {
        var telemetry = JsonlFileTelemetry.create(tracesDir);
        var h = AgentHarness.create(new HarnessConfig(
                streamFn("assistant reply"), MODEL, ModelThinkingLevel.off(), "",
                Set.of(), 200_000, null, null, null,
            null, java.util.Map.of(),
                com.pijava.ai.http.RetryPolicy.defaultPolicy(),
                telemetry, com.pijava.ai.thinking.ThinkingLevelMap.empty(),
                QueueMode.defaultMode(), QueueMode.defaultMode(), ToolExecution.defaultMode(),
                event -> { }));

        h.prompt("hello");

        var lane = h.snapshot("default");
        var attempts = lane.records().stream()
            .filter(r -> r instanceof LaneRecord.StepAttempt)
            .map(r -> (LaneRecord.StepAttempt) r)
            .toList();
        assertThat(attempts).hasSize(1);
        var attempt = attempts.get(0);
        assertThat(attempt.model()).isEqualTo("faux/test-model");
        assertThat(attempt.messageCount()).isEqualTo(1);
        assertThat(attempt.toolCount()).isEqualTo(0);
        assertThat(attempt.durationMs()).isNotNull();
        assertThat(attempt.durationMs()).isGreaterThanOrEqualTo(0);

        var finishes = lane.records().stream()
            .filter(r -> r instanceof LaneRecord.OperationFinished)
            .map(r -> (LaneRecord.OperationFinished) r)
            .toList();
        assertThat(finishes).isNotEmpty();
        assertThat(finishes.get(finishes.size() - 1).durationMs()).isNotNull();
    }
}
