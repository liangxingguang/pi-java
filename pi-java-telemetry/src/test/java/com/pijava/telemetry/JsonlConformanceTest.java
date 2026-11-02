package com.pijava.telemetry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.pijava.telemetry.testing.TelemetryAdapterConformance;
import com.pijava.telemetry.testing.TelemetryAdapterFixture;

/**
 * 一致性套件挂 {@link JsonlFileTelemetry}（docs/23 §4.3）：fixture 读回
 * JSONL 行重建归一化快照——hex spanId 归一成序号 id、span_end 行序归一成
 * endSequence、JSON 数字归一成 long（pi fixture 的「normalized snapshot
 * reader」职责，{@code testing/types.ts:4}）。
 */
class JsonlConformanceTest {

    @TempDir
    private Path tempDir;

    @TestFactory
    Collection<DynamicTest> conformance() {
        return TelemetryAdapterConformance.createTelemetryAdapterConformance(
                () -> new JsonlTraceFixture(Files.createTempDirectory(tempDir, "case")))
            .stream()
            .map(testCase -> DynamicTest.dynamicTest(
                testCase.group() + " / " + testCase.name(),
                () -> testCase.run().run()))
            .toList();
    }

    /** JSONL 读回 fixture。每个用例一个目录，与 adapter 实例同生命周期。 */
    static final class JsonlTraceFixture implements TelemetryAdapterFixture {

        private static final ObjectMapper MAPPER = new ObjectMapper();
        private static final TypeReference<Map<String, Object>> LINE_TYPE =
            new TypeReference<>() { };

        private final Path dir;
        private final JsonlFileTelemetry context;

        JsonlTraceFixture(Path dir) {
            this.dir = dir;
            this.context = JsonlFileTelemetry.create(dir);
        }

        @Override
        public TelemetryContext context() {
            return context;
        }

        @Override
        public TelemetryMetrics metrics() {
            return context.metrics();
        }

        @Override
        public List<RecordedTelemetrySpan> getSpans() throws IOException {
            return reconstruct(traceLines());
        }

        @Override
        public List<RecordedTelemetryMeasurement> getMetrics() throws IOException {
            return reconstructMetrics(traceLines());
        }

        private List<String> traceLines() throws IOException {
            Path file;
            try (var files = Files.list(dir)) {
                file = files
                    .filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                    .findFirst()
                    .orElse(null);
            }
            return file == null ? List.of() : Files.readAllLines(file, StandardCharsets.UTF_8);
        }

        private static List<RecordedTelemetrySpan> reconstruct(List<String> lines)
            throws IOException
        {
            var byId = new LinkedHashMap<String, SpanBuilder>();
            var order = new ArrayList<SpanBuilder>();
            long endSequence = 0;
            for (var line : lines) {
                var json = MAPPER.readValue(line, LINE_TYPE);
                switch ((String) json.get("kind")) {
                    case "span_start" -> {
                        var builder = new SpanBuilder();
                        builder.seqId = order.size() + 1L;
                        builder.spanId = (String) json.get("spanId");
                        builder.traceId = (String) json.get("traceId");
                        builder.parentSpanId = (String) json.get("parentSpanId");
                        builder.name = (String) json.get("name");
                        builder.startAttributes = normalizeMap(json.get("attrs"));
                        byId.put(builder.spanId, builder);
                        order.add(builder);
                    }
                    case "span_end" -> {
                        var builder = byId.get((String) json.get("spanId"));
                        builder.settled = true;
                        builder.endSequence = ++endSequence;
                        builder.endAttributes = normalizeMap(json.get("attrs"));
                        builder.status = "error".equals(json.get("status"))
                            ? new SpanStatus.Error(
                                (String) json.get("errorName"),
                                (String) json.get("errorMessage"))
                            : new SpanStatus.Ok();
                    }
                    case "event" -> {
                        // addEvent 行（attrs 键）；recordEvent 行（payload 键）不属于跨度事件。
                        if (json.containsKey("attrs")) {
                            var builder = byId.get((String) json.get("spanId"));
                            builder.events.add(new RecordedTelemetryEvent(
                                (String) json.get("name"), normalizeMap(json.get("attrs"))));
                        }
                    }
                    default -> {
                        // counter / timing 行与跨度快照无关。
                    }
                }
            }
            var spans = new ArrayList<RecordedTelemetrySpan>(order.size());
            for (var builder : order) {
                spans.add(new RecordedTelemetrySpan(
                    builder.seqId,
                    builder.parentSpanId != null ? byId.get(builder.parentSpanId).seqId : null,
                    carrier(builder),
                    builder.parentSpanId == null ? null : carrier(byId.get(builder.parentSpanId)),
                    builder.name,
                    builder.settled ? builder.endAttributes : builder.startAttributes,
                    builder.events,
                    builder.status,
                    builder.settled,
                    builder.settled
                        ? OptionalLong.of(builder.endSequence) : OptionalLong.empty()));
            }
            return spans;
        }

        private static Map<String, String> carrier(SpanBuilder builder) {
            return Map.of("traceId", builder.traceId, "spanId", builder.spanId);
        }

        private static List<RecordedTelemetryMeasurement> reconstructMetrics(List<String> lines)
            throws IOException
        {
            var measurements = new ArrayList<RecordedTelemetryMeasurement>();
            for (var line : lines) {
                var json = MAPPER.readValue(line, LINE_TYPE);
                if (!"metric".equals(json.get("kind"))) {
                    continue;
                }
                var kind = switch ((String) json.get("metricKind")) {
                    case "counter" -> MetricKind.COUNTER;
                    case "upDownCounter" -> MetricKind.UP_DOWN_COUNTER;
                    case "histogram" -> MetricKind.HISTOGRAM;
                    default -> throw new AssertionError("Unknown metric kind");
                };
                measurements.add(new RecordedTelemetryMeasurement(
                    kind,
                    (String) json.get("name"),
                    (String) json.get("description"),
                    (String) json.get("unit"),
                    normalizeBoundaries(json.get("boundaries")),
                    ((Number) json.get("value")).doubleValue(),
                    normalizeMap(json.get("attrs")),
                    normalizeCarrier(json.get("exemplar"))));
            }
            return List.copyOf(measurements);
        }

        private static List<Double> normalizeBoundaries(Object value) {
            if (!(value instanceof List<?> list)) {
                return null;
            }
            return list.stream().map(number -> ((Number) number).doubleValue()).toList();
        }

        private static Map<String, String> normalizeCarrier(Object value) {
            if (!(value instanceof Map<?, ?> map)) {
                return null;
            }
            return Map.of(
                "traceId", (String) map.get("traceId"),
                "spanId", (String) map.get("spanId"));
        }

        private static Map<String, Object> normalizeMap(Object value) {
            var normalized = new LinkedHashMap<String, Object>();
            if (value instanceof Map<?, ?> map) {
                for (var entry : map.entrySet()) {
                    normalized.put((String) entry.getKey(), normalizeValue(entry.getValue()));
                }
            }
            return normalized;
        }

        /** JSON 数字统一成 long，与参考实现的整数字面值可比较。 */
        private static Object normalizeValue(Object value) {
            if (value instanceof Number number) {
                return number.longValue();
            }
            if (value instanceof Map<?, ?>) {
                return normalizeMap(value);
            }
            if (value instanceof List<?> list) {
                var normalized = new ArrayList<>(list.size());
                for (var element : list) {
                    normalized.add(normalizeValue(element));
                }
                return normalized;
            }
            return value;
        }

        private static final class SpanBuilder {
            private long seqId;
            private String spanId;
            private String traceId;
            private String parentSpanId;
            private String name;
            private Map<String, Object> startAttributes = Map.of();
            private Map<String, Object> endAttributes = Map.of();
            private final List<RecordedTelemetryEvent> events = new ArrayList<>();
            private SpanStatus status = new SpanStatus.Ok();
            private boolean settled;
            private long endSequence;
        }
    }
}
