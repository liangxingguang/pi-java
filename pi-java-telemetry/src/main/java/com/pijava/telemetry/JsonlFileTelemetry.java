package com.pijava.telemetry;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.function.Function;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link TelemetryContext} that appends structured JSONL trace lines to a
 * per-process trace file under a traces directory
 * ({@code trace-<sessionId>-<timestamp>.jsonl}).
 *
 * <p>All file IO is best-effort: a write failure logs a warning once and the
 * exporter degrades to a no-op — telemetry must never break the main flow.</p>
 *
 * <p>Line kinds: {@code span_start}, {@code span_end}, {@code event},
 * {@code counter}, {@code timing}.  Dimensions registered via {@link #with}
 * are merged into every line.  Child spans opened from a {@link TelemetrySpan}
 * inherit its trace/span ids as parent; {@link #pushCurrent}/{@link
 * #popCurrent} let a thread bind event lines to a span opened on another
 * thread.  Bindings are <b>per thread</b>: an event recorded without a binding
 * on its own thread is left unattributed rather than hanging off a foreign
 * span.</p>
 */
public final class JsonlFileTelemetry implements TelemetryContext {

    private static final Logger LOG = LoggerFactory.getLogger(JsonlFileTelemetry.class);
    private static final DateTimeFormatter FILE_TS =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final int ROOT_TRACE_ID_RADIX = 16;

    private final ObjectMapper mapper = new ObjectMapper();
    private final SplittableRandom random = new SplittableRandom();
    private final Path tracesDir;
    private final boolean recordPayloads;

    private final Object lock = new Object();
    private BufferedWriter writer;
    private Path file;
    private boolean degraded;
    private final Map<String, String> dimensions;

    /**
     * Per-thread stack of spans bound via {@link #pushCurrent}, whose top is the
     * host span of {@link #recordEvent} lines recorded on that same thread.
     *
     * <p><b>Why thread-local.</b> The stack is read and written without holding
     * {@link #lock}, which guards file IO only.  A single shared deque therefore
     * lets concurrent threads corrupt it and, worse, lets a thread read
     * <em>someone else's</em> span — attributing an event to the wrong request
     * silently.  Per-thread stacks turn that mis-attribution into a detectable
     * absence (the event line simply carries no {@code traceId}).</p>
     *
     * <p>Nothing calls {@link ThreadLocal#remove()}: a thread that pushed and
     * popped leaves an empty deque behind, which costs nothing and dies with the
     * thread (production runs each prompt on a fresh virtual thread — see
     * {@code docs/31 §8.25}).</p>
     */
    private final ThreadLocal<Deque<JsonlSpan>> currentStack =
        ThreadLocal.withInitial(ArrayDeque::new);

    private JsonlFileTelemetry(Path tracesDir, boolean recordPayloads, Map<String, String> dimensions) {
        this.tracesDir = tracesDir;
        this.recordPayloads = recordPayloads;
        this.dimensions = Map.copyOf(dimensions);
    }

    /**
     * Create an exporter writing into the given traces directory; the trace
     * file is created lazily on first output.
     *
     * @param tracesDir directory for trace files (created if missing)
     * @return the telemetry context
     */
    public static JsonlFileTelemetry create(Path tracesDir) {
        return new JsonlFileTelemetry(tracesDir, false, Map.of());
    }

    /**
     * Whether {@link #recordEvent} lines should actually be written.  When
     * false (default) event recording is skipped entirely — used to
     * implement the {@code --trace-payloads} opt-in without threading a
     * flag through call sites.
     *
     * @param recordPayloads true to write event lines
     * @return a context that records events
     */
    public JsonlFileTelemetry withPayloads(boolean recordPayloads) {
        return new JsonlFileTelemetry(tracesDir, recordPayloads, dimensions);
    }

    /** Whether event lines are recorded. */
    @Override
    public boolean recordsPayloads() {
        return recordPayloads;
    }

    @Override
    public <T> T startSpan(SpanOptions options, Function<? super TelemetrySpan, ? extends T> body) {
        var span = openSpan(options);
        pushCurrent(span);
        try {
            return body.apply(span);
        } catch (RuntimeException | Error e) {
            if (span instanceof JsonlSpan js) {
                js.markError();
            }
            throw e;
        } finally {
            // Symmetric with the push above.  Without it the stack grows without
            // bound and a recordEvent after the callback returns would bind to an
            // already-ended span.  Unbinding is paired (popCurrent peeks first), so
            // an inner span returning leaves an outer binding intact.
            popCurrent(span);
            span.close();
        }
    }

    @Override
    public TelemetrySpan openSpan(SpanOptions options) {
        return new JsonlSpan(nextSpanId(), options, null);
    }

    @Override
    public void incrementCounter(String name, long delta) {
        writeMetric("counter", name, delta);
    }

    @Override
    public void recordTiming(String name, long durationMs) {
        writeMetric("timing", name, durationMs);
    }

    @Override
    public TelemetryContext with(String key, String value) {
        var merged = new LinkedHashMap<>(dimensions);
        merged.put(key, value);
        return new JsonlFileTelemetry(tracesDir, recordPayloads, merged);
    }

    /**
     * Record an event line bound to the current span (see {@link
     * #pushCurrent}).  Skipped entirely unless payloads are enabled via
     * {@link #withPayloads(boolean)}.
     *
     * @param name event name (e.g. "llm.payload.request")
     * @param payload payload map (may contain nested structures)
     */
    @Override
    public void recordEvent(String name, Map<String, Object> payload) {
        if (!recordPayloads) {
            return;
        }
        var current = currentStack.get().peek();
        String traceId = current != null ? current.traceId : null;
        String spanId = current != null ? current.spanId : null;
        var line = baseLine("event", traceId);
        if (spanId != null) {
            line.put("spanId", spanId);
        }
        line.put("name", name);
        line.put("payload", sanitize(payload));
        writeLine(line);
    }

    /**
     * Bind a span (opened on this or another thread) as the current span for
     * event recording <b>on this thread</b>.  Must be paired with {@link
     * #popCurrent} in a finally block.
     *
     * @param span the span to bind
     */
    @Override
    public void pushCurrent(TelemetrySpan span) {
        if (span instanceof JsonlSpan js) {
            currentStack.get().push(js);
        }
    }

    /**
     * Remove a span previously bound with {@link #pushCurrent} on this thread.
     * Only pops if it is the current top (guards against unbalanced pairs).
     *
     * @param span the span to unbind
     */
    @Override
    public void popCurrent(TelemetrySpan span) {
        if (span instanceof JsonlSpan js) {
            var stack = currentStack.get();
            if (stack.peek() == js) {
                stack.pop();
            }
        }
    }

    private void writeMetric(String kind, String name, long value) {
        var line = baseLine(kind, null);
        line.put("name", name);
        line.put(kind.equals("counter") ? "delta" : "durationMs", value);
        writeLine(line);
    }

    private LinkedHashMap<String, Object> baseLine(String kind, String traceId) {
        var line = new LinkedHashMap<String, Object>();
        line.put("kind", kind);
        line.put("ts", Instant.now().toString());
        if (traceId != null) {
            line.put("traceId", traceId);
        }
        if (!dimensions.isEmpty()) {
            line.putAll(dimensions);
        }
        return line;
    }

    private void writeLine(LinkedHashMap<String, Object> line) {
        synchronized (lock) {
            if (degraded) {
                return;
            }
            try {
                var w = ensureWriter();
                w.write(mapper.writeValueAsString(line));
                w.newLine();
                w.flush();
            } catch (IOException | UncheckedIOException e) {
                degraded = true;
                LOG.warn("Trace file write failed; telemetry degraded to no-op: {}", e.toString());
            }
        }
    }

    private BufferedWriter ensureWriter() throws IOException {
        if (writer == null) {
            Files.createDirectories(tracesDir);
            String sessionId = dimensions.getOrDefault("sessionId", "unknown");
            String fileName = "trace-" + sessionId + "-" + FILE_TS.format(Instant.now()) + ".jsonl";
            file = tracesDir.resolve(fileName);
            writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        return writer;
    }

    private String nextSpanId() {
        return String.format("%08x", (long) random.nextInt() & 0xFFFFFFFFL);
    }

    private static Map<String, Object> sanitize(Map<String, Object> attrs) {
        if (attrs.isEmpty()) {
            return Map.of();
        }
        var out = new LinkedHashMap<String, Object>();
        for (var entry : attrs.entrySet()) {
            if (entry.getValue() != null) {
                out.put(entry.getKey(), entry.getValue());
            }
        }
        return out;
    }

    private final class JsonlSpan implements TelemetrySpan {

        private final String traceId;
        private final String spanId;
        private final JsonlSpan parent;
        private final String name;
        private final long startNanos = System.nanoTime();
        private final Map<String, Object> startAttrs;
        private final Map<String, Object> lateAttrs = new LinkedHashMap<>();
        private String status = "ok";
        private boolean ended;

        private JsonlSpan(String spanId, SpanOptions options, JsonlSpan parent) {
            this.spanId = spanId;
            this.parent = parent;
            this.name = options.name();
            this.traceId = parent != null ? parent.traceId : newTraceId();
            this.startAttrs = options.attributes();
            emitStart();
        }

        private void emitStart() {
            var line = baseLine("span_start", traceId);
            line.put("spanId", spanId);
            line.put("parentSpanId", parent != null ? parent.spanId : null);
            line.put("name", name);
            line.put("attrs", sanitize(startAttrs));
            writeLine(line);
        }

        @Override
        public void addAttribute(String key, Object value) {
            if (value != null) {
                lateAttrs.put(key, value);
            }
        }

        private void markError() {
            status = "error";
        }

        /** Mark this span as aborted (user cancellation rather than failure). */
        public void markAborted() {
            status = "aborted";
        }

        @Override
        public void close() {
            if (ended) {
                return;
            }
            ended = true;
            var attrs = new LinkedHashMap<String, Object>();
            attrs.putAll(sanitize(startAttrs));
            attrs.putAll(sanitize(lateAttrs));
            var line = baseLine("span_end", traceId);
            line.put("spanId", spanId);
            line.put("durationMs", (System.nanoTime() - startNanos) / 1_000_000);
            line.put("status", status);
            line.put("attrs", attrs);
            writeLine(line);
        }

        @Override
        public <T> T startSpan(SpanOptions options, Function<? super TelemetrySpan, ? extends T> body) {
            var child = new JsonlSpan(nextSpanId(), options, this);
            try {
                return body.apply(child);
            } catch (RuntimeException | Error e) {
                child.markError();
                throw e;
            } finally {
                child.close();
            }
        }

        @Override
        public TelemetrySpan openSpan(SpanOptions options) {
            return new JsonlSpan(nextSpanId(), options, this);
        }

        @Override
        public void incrementCounter(String name, long delta) {
            JsonlFileTelemetry.this.incrementCounter(name, delta);
        }

        @Override
        public void recordTiming(String name, long durationMs) {
            JsonlFileTelemetry.this.recordTiming(name, durationMs);
        }

        @Override
        public TelemetryContext with(String key, String value) {
            return JsonlFileTelemetry.this.with(key, value);
        }
    }

    private String newTraceId() {
        return Long.toUnsignedString(random.nextLong(), ROOT_TRACE_ID_RADIX);
    }
}
