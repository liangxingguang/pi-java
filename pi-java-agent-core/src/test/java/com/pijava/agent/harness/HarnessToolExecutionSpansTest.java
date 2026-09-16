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
     * StreamFn: the first LLM call replies with the given assistant message
     * (carrying the batch's tool_use blocks), every later call stops — so the
     * harness executes the batch once and the run finishes instead of looping
     * on tool_use forever.
     */
    private static StreamFn toolUseThenStopStreamFn(AssistantMessage toolUse) {
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

    /** Single-call batch convenience overload. */
    private static StreamFn toolUseThenStopStreamFn(String toolCallId, String toolName) {
        return toolUseThenStopStreamFn(AssistantMessage.empty()
                .withContent(List.of(new ContentBlock.ToolUseContent(
                    toolCallId, toolName, Map.of("text", "hello"))))
                .withStopReason("tool_use"));
    }

    private static AgentHarness harness(JsonlFileTelemetry telemetry,
            ToolRegistry registry, StreamFn streamFn) {
        return harness(telemetry, registry, streamFn, ToolExecution.defaultMode());
    }

    /**
     * 同上，但可指定工具批次的执行模式。
     *
     * <p>这里必须**显式**给模式：夹具的 {@code activeTools} 是空的，于是
     * {@code PiLoopTools.useSequentialPath} 的「工具自带 {@code ExecutionMode.Sequential}
     * 即整批降级」那条分支查不到工具（{@code Context.toolNamed} 走的是 {@code context.tools}，
     * 不是注册表），只剩模式这一条判据。工具本身照旧按名从注册表取，所以能正常执行。</p>
     */
    private static AgentHarness harness(JsonlFileTelemetry telemetry, ToolRegistry registry,
            StreamFn streamFn, ToolExecution toolExecution) {
        return AgentHarness.create(new HarnessConfig(
                streamFn, MODEL, ModelThinkingLevel.off(), "",
                Set.of(), 200_000, registry, null, null,
            null, Map.of(),
                com.pijava.ai.http.RetryPolicy.defaultPolicy(),
                telemetry, com.pijava.ai.thinking.ThinkingLevelMap.empty(),
                QueueMode.defaultMode(), QueueMode.defaultMode(), toolExecution,
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

    /**
     * 顺序路径的**多调用**批次：`batchSize` 是本批的调用**总数**，不是前缀数
     * （{@code docs/31 §8.26.5-11}）。
     *
     * <p>上面的单调用用例两种读法同值（{@code batchSize == 1}），测不出这个缺陷；
     * 双调用的顺序批才把它们分开 —— 旧实现读的是「结果消息落定时已 start 过的个数」，
     * 首个调用收尾时只有它自己登记过 ⇒ 会记成 1。</p>
     *
     * <p>第一条断言同时钉住了「走的确实是顺序路径」：逐调用成组是那个读法出错的
     * **前提**，也是 pi 自己的录制形状（{@code conformance/pi-out/S10.pi.jsonl:11-18}）。</p>
     */
    @Test
    void sequentialBatchReportsTheWholeBatchSizeOnEverySpan(@TempDir Path tracesDir) throws IOException {
        var registry = new ToolRegistry(null);
        registry.register(echoTool("echo-a"));
        registry.register(echoTool("echo-b"));
        var telemetry = JsonlFileTelemetry.create(tracesDir);
        var batch = AssistantMessage.empty()
            .withContent(List.of(
                new ContentBlock.ToolUseContent("call-a", "echo-a", Map.of("text", "one")),
                new ContentBlock.ToolUseContent("call-b", "echo-b", Map.of("text", "two"))))
            .withStopReason("tool_use");
        var h = harness(telemetry, registry, toolUseThenStopStreamFn(batch),
            new ToolExecution.Sequential());

        h.prompt("run two tools");

        var lines = readLines(tracesDir);
        var toolEvents = new ArrayList<String>();
        for (var line : lines) {
            String kind = line.get("kind").asText();
            if (("span_start".equals(kind) || "span_end".equals(kind))
                    && line.get("attrs").has("toolCallId")) {
                toolEvents.add(kind + ":" + line.get("attrs").get("toolCallId").asText());
            }
        }
        assertThat(toolEvents).containsExactly(
            "span_start:call-a", "span_end:call-a",
            "span_start:call-b", "span_end:call-b");

        var toolEnds = lines.stream()
            .filter(n -> "span_end".equals(n.get("kind").asText())
                && n.get("attrs").has("toolCallId"))
            .toList();
        assertThat(toolEnds)
            .extracting(n -> n.get("attrs").get("toolIndex").asInt())
            .containsExactly(0, 1);
        for (var end : toolEnds) {
            assertThat(end.get("attrs").get("batchSize").asInt())
                .as("每个调用都记本批总数（callId=%s）",
                    end.get("attrs").get("toolCallId").asText())
                .isEqualTo(2);
        }
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
