package com.pijava.telemetry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/** JsonlFileTelemetry writes span_start/span_end/event/counter JSONL lines. */
class JsonlFileTelemetryTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    private List<JsonNode> readLines(Path file) throws IOException {
        var lines = new ArrayList<JsonNode>();
        for (String line : Files.readAllLines(file)) {
            if (!line.isBlank()) {
                lines.add(mapper.readTree(line));
            }
        }
        return lines;
    }

    private Path onlyTraceFile() throws IOException {
        try (var stream = Files.list(tempDir)) {
            var files = stream.filter(p -> p.getFileName().toString().endsWith(".jsonl")).toList();
            assertThat(files).hasSize(1);
            return files.get(0);
        }
    }

    @Test
    void startSpanWritesSpanStartAndEndLines() throws IOException {
        var telemetry = JsonlFileTelemetry.create(tempDir);

        String result = telemetry.startSpan(
            new SpanOptions("llm.request", Map.of("model", "claude-sonnet-4-6")),
            span -> {
                span.addAttribute("stopReason", "tool_use");
                return "done";
            });

        assertThat(result).isEqualTo("done");
        var lines = readLines(onlyTraceFile());
        assertThat(lines).hasSize(2);

        var start = lines.get(0);
        assertThat(start.get("kind").asText()).isEqualTo("span_start");
        assertThat(start.get("name").asText()).isEqualTo("llm.request");
        assertThat(start.get("spanId").asText()).isNotBlank();
        assertThat(start.get("parentSpanId").isNull()).isTrue();
        assertThat(start.get("traceId").asText()).isNotBlank();
        assertThat(start.get("ts").asText()).isNotBlank();
        assertThat(start.get("attrs").get("model").asText()).isEqualTo("claude-sonnet-4-6");

        var end = lines.get(1);
        assertThat(end.get("kind").asText()).isEqualTo("span_end");
        assertThat(end.get("spanId").asText()).isEqualTo(start.get("spanId").asText());
        assertThat(end.get("durationMs").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(end.get("status").asText()).isEqualTo("ok");
        assertThat(end.get("attrs").get("stopReason").asText()).isEqualTo("tool_use");
    }

    @Test
    void nestedSpansCarryParentSpanId() throws IOException {
        var telemetry = JsonlFileTelemetry.create(tempDir);

        telemetry.startSpan(new SpanOptions("outer"), outer ->
            outer.startSpan(new SpanOptions("inner"), inner -> null));

        var lines = readLines(onlyTraceFile());
        var outerStart = lines.stream()
            .filter(n -> "span_start".equals(n.get("kind").asText())
                && "outer".equals(n.get("name").asText()))
            .findFirst().orElseThrow();
        var innerStart = lines.stream()
            .filter(n -> "span_start".equals(n.get("kind").asText())
                && "inner".equals(n.get("name").asText()))
            .findFirst().orElseThrow();
        assertThat(innerStart.get("parentSpanId").asText())
            .isEqualTo(outerStart.get("spanId").asText());
        assertThat(innerStart.get("traceId").asText())
            .isEqualTo(outerStart.get("traceId").asText());
    }

    @Test
    void openSpanAndCloseEmitsPairedLinesWithStatus() throws IOException {
        var telemetry = JsonlFileTelemetry.create(tempDir);

        var span = telemetry.openSpan(new SpanOptions("harness.run", Map.of("lane", "default")));
        span.addAttribute("outcome", "completed");
        span.close();
        span.close(); // idempotent: no second span_end

        var lines = readLines(onlyTraceFile());
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).get("name").asText()).isEqualTo("harness.run");
        assertThat(lines.get(0).get("attrs").get("lane").asText()).isEqualTo("default");
        assertThat(lines.get(1).get("attrs").get("outcome").asText()).isEqualTo("completed");
    }

    @Test
    void errorInBodyMarksSpanEndStatusError() throws IOException {
        var telemetry = JsonlFileTelemetry.create(tempDir);

        try {
            telemetry.startSpan(new SpanOptions("llm.request"), span -> {
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException expected) {
            // expected
        }

        var lines = readLines(onlyTraceFile());
        assertThat(lines).hasSize(2);
        assertThat(lines.get(1).get("status").asText()).isEqualTo("error");
    }

    @Test
    void dimensionsAreMergedIntoEveryLine() throws IOException {
        var telemetry = JsonlFileTelemetry.create(tempDir).with("sessionId", "sess-1");

        telemetry.startSpan(new SpanOptions("llm.request"), span -> null);
        telemetry.incrementCounter("harness.turn", 1);
        telemetry.recordTiming("llm.request.duration", 42);

        var lines = readLines(onlyTraceFile());
        assertThat(lines).hasSize(4);
        assertThat(lines.get(0).get("sessionId").asText()).isEqualTo("sess-1");
        assertThat(lines.get(2).get("sessionId").asText()).isEqualTo("sess-1");
        assertThat(lines.get(3).get("sessionId").asText()).isEqualTo("sess-1");
    }

    @Test
    void counterAndTimingWriteMetricLines() throws IOException {
        var telemetry = JsonlFileTelemetry.create(tempDir);

        telemetry.incrementCounter("harness.turn", 1);
        telemetry.recordTiming("llm.request.duration", 2314);

        var lines = readLines(onlyTraceFile());
        assertThat(lines).hasSize(2);

        var counter = lines.get(0);
        assertThat(counter.get("kind").asText()).isEqualTo("counter");
        assertThat(counter.get("name").asText()).isEqualTo("harness.turn");
        assertThat(counter.get("delta").asLong()).isEqualTo(1);

        var timing = lines.get(1);
        assertThat(timing.get("kind").asText()).isEqualTo("timing");
        assertThat(timing.get("name").asText()).isEqualTo("llm.request.duration");
        assertThat(timing.get("durationMs").asLong()).isEqualTo(2314);
    }

    @Test
    void recordEventWritesEventLineWithCurrentSpanContext() throws IOException {
        var telemetry = JsonlFileTelemetry.create(tempDir).withPayloads(true);

        telemetry.startSpan(new SpanOptions("llm.request"), span -> {
            telemetry.recordEvent("llm.payload.request", Map.of("messages", 14));
            return null;
        });

        var lines = readLines(onlyTraceFile());
        assertThat(lines).hasSize(3);
        var event = lines.get(1);
        assertThat(event.get("kind").asText()).isEqualTo("event");
        assertThat(event.get("name").asText()).isEqualTo("llm.payload.request");
        assertThat(event.get("traceId").asText()).isEqualTo(lines.get(0).get("traceId").asText());
        assertThat(event.get("spanId").asText()).isEqualTo(lines.get(0).get("spanId").asText());
        assertThat(event.get("payload").get("messages").asInt()).isEqualTo(14);
    }

    @Test
    void recordEventWithoutPayloadsIsSkipped() throws IOException {
        var telemetry = JsonlFileTelemetry.create(tempDir);

        telemetry.recordEvent("llm.payload.request", Map.of("k", "v"));

        try (var stream = Files.list(tempDir)) {
            assertThat(stream.filter(p -> p.getFileName().toString().endsWith(".jsonl")).count())
                .isZero();
        }
    }

    @Test
    void recordEventWithoutActiveSpanHasNoSpanId() throws IOException {
        var telemetry = JsonlFileTelemetry.create(tempDir).withPayloads(true);

        telemetry.recordEvent("llm.payload.request", Map.of());

        var lines = readLines(onlyTraceFile());
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).get("kind").asText()).isEqualTo("event");
        assertThat(lines.get(0).has("traceId")).isFalse();
        assertThat(lines.get(0).has("spanId")).isFalse();
    }

    @Test
    void pushCurrentBindsEventToGivenSpan() throws IOException {
        var telemetry = JsonlFileTelemetry.create(tempDir).withPayloads(true);

        telemetry.startSpan(new SpanOptions("harness.run"), root -> {
            var worker = root.openSpan(new SpanOptions("tool.execute"));
            telemetry.pushCurrent(worker);
            try {
                telemetry.recordEvent("llm.payload.response", Map.of());
            } finally {
                telemetry.popCurrent(worker);
                worker.close();
            }
            return null;
        });

        var lines = readLines(onlyTraceFile());
        var event = lines.stream().filter(n -> "event".equals(n.get("kind").asText())).findFirst().orElseThrow();
        var toolStart = lines.stream()
            .filter(n -> "span_start".equals(n.get("kind").asText())
                && "tool.execute".equals(n.get("name").asText()))
            .findFirst().orElseThrow();
        assertThat(event.get("spanId").asText()).isEqualTo(toolStart.get("spanId").asText());
        assertThat(event.get("traceId").asText()).isEqualTo(toolStart.get("traceId").asText());
    }

    @Test
    void ioFailureIsSilentlySwallowed() throws IOException {
        // A file where a directory is expected forces writer creation to fail.
        Path blocker = tempDir.resolve("not-a-dir");
        Files.writeString(blocker, "occupied");

        var fileTelemetry = JsonlFileTelemetry.create(blocker);
        assertThatNoException().isThrownBy(() -> {
            fileTelemetry.startSpan(new SpanOptions("llm.request"), span -> "x");
            fileTelemetry.incrementCounter("harness.turn", 1);
        });
    }

    @Test
    void nullAttributeValuesAreSkipped() throws IOException {
        var telemetry = JsonlFileTelemetry.create(tempDir);

        var span = telemetry.openSpan(new SpanOptions("harness.run"));
        span.addAttribute("costTotal", null);
        span.close();

        var lines = readLines(onlyTraceFile());
        var end = lines.get(1);
        assertThat(end.get("attrs").has("costTotal")).isFalse();
    }

    @Test
    void subsequentCallsReuseSameFile() throws IOException {
        var telemetry = JsonlFileTelemetry.create(tempDir);
        telemetry.incrementCounter("a", 1);
        telemetry.incrementCounter("b", 1);

        var lines = readLines(onlyTraceFile());
        assertThat(lines).hasSize(2);
    }
}
