package com.pijava.telemetry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.catchThrowable;

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

    /**
     * 跨线程归属（{@code docs/31 §8.25.6} ①-a）：A 线程绑定的跨度对 B 线程不可见。
     *
     * <p>夹具是确定性的 —— A 推入后在 finally 里才弹出，B 在 join 之前运行，因此
     * 共享栈的旧实现必然让 B 读到 A 的跨度（RE-1 的牙）。</p>
     *
     * <p><b>正向对照不可省</b>：没有 {@code on=owner} 那条断言，「{@code spanId}
     * 永远为 null」也能让后半段通过。</p>
     */
    @Test
    void recordEventOnlySeesSpansBoundOnItsOwnThread() throws Exception {
        var telemetry = JsonlFileTelemetry.create(tempDir).withPayloads(true);
        var span = telemetry.openSpan(new SpanOptions("harness.run"));
        telemetry.pushCurrent(span);
        AtomicReference<Throwable> foreignFailure = new AtomicReference<>();
        try {
            telemetry.recordEvent("llm.payload.request", Map.of("on", "owner"));
            var other = Thread.ofVirtual().start(() -> {
                try {
                    telemetry.recordEvent("llm.payload.request", Map.of("on", "foreign"));
                } catch (Throwable t) {
                    foreignFailure.set(t);
                }
            });
            other.join();
            assertThat(foreignFailure.get()).isNull();
        } finally {
            telemetry.popCurrent(span);
            span.close();
        }

        var lines = readLines(onlyTraceFile());
        assertThat(lines).hasSize(4);
        var spanStart = lines.stream()
            .filter(n -> "span_start".equals(n.get("kind").asText()))
            .findFirst().orElseThrow();

        // 正向对照：绑定线程自己记的事件带上该跨度
        var owner = eventMarkedOn(lines, "owner");
        assertThat(owner.get("spanId").asText()).isEqualTo(spanStart.get("spanId").asText());
        assertThat(owner.get("traceId").asText()).isEqualTo(spanStart.get("traceId").asText());

        // 被钉住的一条：另一线程读不到别人的绑定，宁可无归属也不挂错的跨度
        var foreign = eventMarkedOn(lines, "foreign");
        assertThat(foreign.has("spanId")).isFalse();
        assertThat(foreign.has("traceId")).isFalse();
    }

    /**
     * A2 的针（{@code docs/31 §8.25.6} ④）：{@code startSpan} 回调返回后解绑 ——
     * 此前它只 push 不 pop，栈无界增长，且此后的事件行会绑到**已结束**的跨度上。
     */
    @Test
    void startSpanUnbindsItsSpanWhenTheCallbackReturns() throws IOException {
        var telemetry = JsonlFileTelemetry.create(tempDir).withPayloads(true);

        telemetry.startSpan(new SpanOptions("llm.request"), span -> null);
        telemetry.recordEvent("llm.payload.request", Map.of("on", "after"));

        var lines = readLines(onlyTraceFile());
        var after = eventMarkedOn(lines, "after");
        assertThat(after.has("spanId")).isFalse();
        assertThat(after.has("traceId")).isFalse();
    }

    /**
     * A2 的一半：解绑是**配对**的（{@code popCurrent} 的 {@code peek() == span} 守卫），
     * 内层 span 返回不会把外层的绑定一起弹掉。
     *
     * <p>⚠️ 两层都必须从 {@link JsonlFileTelemetry#startSpan} 进：{@code JsonlSpan.startSpan}
     * 根本不碰当前栈（登记为 {@code docs/31 §8.25.5-9}），从 span 对象进就测不到这个守卫。</p>
     */
    @Test
    void innerSpanUnbindingLeavesOuterSpanBound() throws IOException {
        var telemetry = JsonlFileTelemetry.create(tempDir).withPayloads(true);

        telemetry.startSpan(new SpanOptions("outer"), outer -> {
            telemetry.startSpan(new SpanOptions("inner"), inner -> null);
            telemetry.recordEvent("llm.payload.request", Map.of("on", "nested"));
            return null;
        });

        var lines = readLines(onlyTraceFile());
        var outerStart = lines.stream()
            .filter(n -> "span_start".equals(n.get("kind").asText())
                && "outer".equals(n.get("name").asText()))
            .findFirst().orElseThrow();
        var nested = eventMarkedOn(lines, "nested");
        assertThat(nested.get("spanId").asText()).isEqualTo(outerStart.get("spanId").asText());
    }

    /**
     * pi 的 adapter 契约「makes calls after settlement inert」的子跨度那一半
     * （{@code docs/31 §8.28.4} 第 6 条）：父已结算后开子跨度，回调照跑一次、
     * 返回值与异常原样穿透，但**什么都不记** —— pi 是把它降级成 noop 上下文
     * （{@code packages/telemetry/src/memory.ts:126}），而不是记在已结算的父下面。
     */
    @Test
    void childSpanOpenedAfterItsParentSettledIsInert() throws IOException {
        var telemetry = JsonlFileTelemetry.create(tempDir);
        var parent = telemetry.openSpan(new SpanOptions("harness.run"));
        parent.close();
        int linesAfterParent = readLines(onlyTraceFile()).size();

        var calls = new AtomicInteger();
        var value = parent.startSpan(new SpanOptions("late-child"), child -> {
            calls.incrementAndGet();
            child.addAttribute("ignored", true);
            return 7;
        });
        var late = parent.openSpan(new SpanOptions("late-open"));
        late.addAttribute("ignored", true);
        late.close();
        var boom = new IllegalStateException("boom");
        var thrown = catchThrowable(() ->
            parent.startSpan(new SpanOptions("late-throw"), child -> {
                throw boom;
            }));

        assertThat(value).as("回调返回值原样穿透").isEqualTo(7);
        assertThat(calls.get()).as("回调仍然同步入场一次").isEqualTo(1);
        assertThat(thrown).as("异常同一对象原样穿透").isSameAs(boom);
        assertThat(readLines(onlyTraceFile())).as("已结算的父下面什么都不记")
            .hasSize(linesAfterParent);
    }

    /** 正向对照：同一次调用在父**未**结算时会落盘 —— 否则上面那条「什么都没记」是空断言。 */
    @Test
    void childSpanOpenedBeforeItsParentSettledIsRecorded() throws IOException {
        var telemetry = JsonlFileTelemetry.create(tempDir);

        telemetry.startSpan(new SpanOptions("outer"), outer ->
            outer.startSpan(new SpanOptions("inner"), inner -> 7));

        assertThat(readLines(onlyTraceFile()))
            .filteredOn(n -> "span_start".equals(n.get("kind").asText()))
            .extracting(n -> n.get("name").asText())
            .containsExactly("outer", "inner");
    }

    /** 按 {@code payload.on} 取出事件行（三条线程归属用例共用的标记位）。 */
    private static JsonNode eventMarkedOn(List<JsonNode> lines, String marker) {
        return lines.stream()
            .filter(n -> "event".equals(n.get("kind").asText()))
            .filter(n -> marker.equals(n.get("payload").get("on").asText()))
            .findFirst().orElseThrow();
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
