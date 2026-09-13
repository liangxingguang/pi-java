package com.pijava.agent.harness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pijava.agent.record.LaneRecord;
import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ExecutionMode;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolRegistry;
import com.pijava.agent.tool.ToolResult;
import com.pijava.agent.tool.ToolUpdateCallback;
import com.pijava.ai.AbortSignal;
import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.telemetry.JsonlFileTelemetry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Observability wiring: a harness run that triggers a tool call emits a
 * {@code tool.execute} span per call (nested under {@code harness.run}),
 * tool.executions/tool.errors counters and tool.execute.duration timings, and
 * writes ToolStarted/ToolFinished audit records sharing the transcript
 * entry's id.
 */
class HarnessToolExecutionSpansTest {

    private static final ModelId<?> MODEL = ModelId.of("faux", "test-model");
    private final ObjectMapper mapper = new ObjectMapper();

    /** A no-op AgentTool that succeeds with the given params string. */
    private static AgentTool<String, Void> echoTool(String name) {
        return new AgentTool<>() {
            @Override public String name() { return name; }
            @Override public String label() { return name; }
            @Override public String description() { return "Echo input"; }
            @Override public Map<String, Object> inputSchema() { return Map.of(); }
            @Override public ExecutionMode executionMode() { return new ExecutionMode.Sequential(); }
            @Override public String prepareArguments(Map<String, Object> raw) {
                return String.valueOf(raw.get("text"));
            }
            @Override public ToolResult<Void> execute(String id, String params, AbortSignal signal,
                    ToolUpdateCallback<Void> onUpdate, ToolContext ctx) {
                return ToolResult.success(params);
            }
        };
    }

    /**
     * StreamFn: first LLM call replies with a tool_use for the named tool,
     * every later call stops — so the harness executes the tool once and the
     * run finishes instead of looping on tool_use forever.
     */
    private static StreamFn toolUseThenStopStreamFn(String toolCallId, String toolName) {
        var toolUse = AssistantMessage.empty()
                .withContent(List.of(new ContentBlock.ToolUseContent(
                    toolCallId, toolName, Map.of("text", "hello"))))
                .withStopReason("tool_use");
        var stop = AssistantMessage.empty()
                .withContent(List.of(new ContentBlock.TextContent("done")))
                .withStopReason("stop");
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        return (model, context, options) -> {
            var partial = calls.incrementAndGet() == 1 ? toolUse : stop;
            return StreamIterator.from(List.of(
                new StreamEvent.Start(AssistantMessage.empty()),
                new StreamEvent.TextEnd(0, "x", partial),
                new StreamEvent.StreamDone(partial.stopReason(), null, partial)));
        };
    }

    private static AgentHarness harness(JsonlFileTelemetry telemetry,
            ToolRegistry registry, StreamFn streamFn) {
        return AgentHarness.create(new HarnessConfig(
                streamFn, MODEL, ModelThinkingLevel.off(), "",
                Set.of(), 200_000, registry, null, null,
            null, Map.of(),
                com.pijava.ai.http.RetryPolicy.defaultPolicy(),
                telemetry, com.pijava.ai.thinking.ThinkingLevelMap.empty(),
                QueueMode.defaultMode(), QueueMode.defaultMode(), ToolExecution.defaultMode(),
                event -> { }));
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
    void toolCallEmitsExecuteSpanCountersAndAuditRecords(@TempDir Path tracesDir) throws IOException {
        var registry = new ToolRegistry(null);
        registry.register(echoTool("echo"));
        var telemetry = JsonlFileTelemetry.create(tracesDir);
        var h = harness(telemetry, registry, toolUseThenStopStreamFn("call-1", "echo"));

        h.prompt("run a tool");

        var lines = readLines(tracesDir);

        // tool.execute span: exactly one, nested under harness.run
        var toolStarts = lines.stream()
            .filter(n -> "span_start".equals(n.get("kind").asText())
                && "tool.execute".equals(n.get("name").asText()))
            .toList();
        assertThat(toolStarts).hasSize(1);
        var runStarts = lines.stream()
            .filter(n -> "span_start".equals(n.get("kind").asText())
                && "harness.run".equals(n.get("name").asText()))
            .toList();
        assertThat(toolStarts.get(0).get("parentSpanId").asText())
            .isEqualTo(runStarts.get(0).get("spanId").asText());

        // span_end carries the tool attributes
        var toolSpanId = toolStarts.get(0).get("spanId").asText();
        var toolEnd = lines.stream()
            .filter(n -> "span_end".equals(n.get("kind").asText())
                && n.get("spanId").asText().equals(toolSpanId))
            .findFirst().orElseThrow();
        var attrs = toolEnd.get("attrs");
        assertThat(attrs.get("toolName").asText()).isEqualTo("echo");
        assertThat(attrs.get("toolIndex").asInt()).isEqualTo(0);
        assertThat(attrs.get("batchSize").asInt()).isEqualTo(1);
        assertThat(attrs.get("allowed").asBoolean()).isTrue();
        assertThat(attrs.get("isError").asBoolean()).isFalse();
        assertThat(attrs.get("terminate").asBoolean()).isFalse();
        assertThat(attrs.get("argsChars").asInt()).isGreaterThanOrEqualTo(0);

        // counters + timing
        assertThat(lines.stream().anyMatch(n -> "counter".equals(n.get("kind").asText())
            && "tool.executions".equals(n.get("name").asText()))).isTrue();
        assertThat(lines.stream().anyMatch(n -> "timing".equals(n.get("kind").asText())
            && "tool.execute.duration".equals(n.get("name").asText()))).isTrue();
        assertThat(lines.stream().noneMatch(n -> "counter".equals(n.get("kind").asText())
            && "tool.errors".equals(n.get("name").asText()))).isTrue();

        // audit records: ToolStarted + ToolFinished share the result entry id
        var lane = h.snapshot("default");
        var started = lane.records().stream()
            .filter(r -> r instanceof LaneRecord.ToolStarted)
            .map(r -> (LaneRecord.ToolStarted) r)
            .toList();
        var finished = lane.records().stream()
            .filter(r -> r instanceof LaneRecord.ToolFinished)
            .map(r -> (LaneRecord.ToolFinished) r)
            .toList();
        assertThat(started).hasSize(1);
        assertThat(finished).hasSize(1);
        assertThat(started.get(0).resultEntryId()).isNotEmpty();
        assertThat(finished.get(0).resultEntryId())
            .isEqualTo(started.get(0).resultEntryId());
        assertThat(finished.get(0).isError()).isFalse();
        assertThat(finished.get(0).terminate()).isFalse();
        assertThat(finished.get(0).durationMs()).isNotNull();
        assertThat(finished.get(0).durationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void failingToolIncrementsToolErrorsCounterAndFlagsRecord(@TempDir Path tracesDir) throws IOException {
        var registry = new ToolRegistry(null);
        registry.register(new AgentTool<String, Void>() {
            @Override public String name() { return "boom"; }
            @Override public String label() { return "boom"; }
            @Override public String description() { return "Always fails"; }
            @Override public Map<String, Object> inputSchema() { return Map.of(); }
            @Override public ExecutionMode executionMode() { return new ExecutionMode.Sequential(); }
            @Override public String prepareArguments(Map<String, Object> raw) { return ""; }
            @Override public ToolResult<Void> execute(String id, String params, AbortSignal signal,
                    ToolUpdateCallback<Void> onUpdate, ToolContext ctx) {
                throw new IllegalStateException("tool failed");
            }
        });
        var telemetry = JsonlFileTelemetry.create(tracesDir);
        var h = harness(telemetry, registry, toolUseThenStopStreamFn("call-1", "boom"));

        h.prompt("run a failing tool");

        var lines = readLines(tracesDir);
        assertThat(lines.stream().anyMatch(n -> "counter".equals(n.get("kind").asText())
            && "tool.errors".equals(n.get("name").asText()))).isTrue();

        var lane = h.snapshot("default");
        var finished = lane.records().stream()
            .filter(r -> r instanceof LaneRecord.ToolFinished)
            .map(r -> (LaneRecord.ToolFinished) r)
            .toList();
        assertThat(finished).hasSize(1);
        assertThat(finished.get(0).isError()).isTrue();
    }
}
