package com.pijava.coding.agent.core;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.pijava.agent.harness.Context;
import com.pijava.agent.harness.StreamFn;
import com.pijava.agent.harness.StreamOptions;
import com.pijava.agent.tool.ToolRegistry;
import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.telemetry.JsonlFileTelemetry;
import com.pijava.telemetry.TelemetryContext;

/**
 * Wraps a {@link StreamFn} to record the LLM request/response payloads as
 * telemetry events ({@code llm.payload.request}/{@code llm.payload.response})
 * when payload recording is enabled (observability design §7.2).
 *
 * <p>The wire bytes are not reachable here ({@code AbstractChatApi.streamInternal}
 * assembles them internally), so the request is serialized from the
 * {@code StreamRequest}-level view handed to the streamFn — model, messages,
 * tool definitions (name + input schema), maxTokens, temperature and thinking
 * config. That is enough to replay a call offline by feeding the recorded
 * messages back through the same streamFn.</p>
 *
 * <p>Recording is opt-in: it delegates to {@link TelemetryContext#recordEvent},
 * a no-op unless the exporter has payload recording enabled. No API key ever
 * appears here — {@code StreamRequest} does not carry {@code ApiOptions}.</p>
 */
final class PayloadRecordingStreamFn implements StreamFn {

    private static final String REQUEST = "llm.payload.request";
    private static final String RESPONSE = "llm.payload.response";

    private final StreamFn inner;
    private final TelemetryContext telemetry;

    /** @param inner      the underlying stream function
     *  @param telemetry  telemetry context (payload recording decides whether events are written) */
    PayloadRecordingStreamFn(StreamFn inner, TelemetryContext telemetry) {
        this.inner = inner;
        this.telemetry = telemetry;
    }

    /**
     * Build the trace exporter used by an assembled session: writes
     * {@code ~/.pi-java/logs/traces/trace-<sessionId>-<ts>.jsonl} with a
     * per-line {@code sessionId} dimension; payload events are only written
     * when {@code --trace-payloads} is set.
     *
     * <p>The same exporter must be handed to the harness ({@code
     * HarnessConfig.telemetry}) so span/timing lines and payload events land in
     * the same file, and so {@code pushCurrent(llmSpan)} (ActionExecutor) makes
     * the payload events bind to the {@code llm.request} span.</p>
     *
     * @param sessionId     session id for the trace file name + per-line dimension
     * @param tracePayloads {@code --trace-payloads}: whether payload events are written
     * @return the exporter
     */
    static TelemetryContext exporter(String sessionId, boolean tracePayloads) {
        return JsonlFileTelemetry.create(tracesDir())
            .withPayloads(tracePayloads)
            .with("sessionId", sessionId == null || sessionId.isBlank()
                ? "unknown" : sessionId);
    }

    /**
     * Trace directory: {@code ~/.pi-java/logs/traces} (design §5.1), overridable
     * with the {@code pi-java.traces.dir} system property (used by tests to
     * keep trace files out of the real home directory).
     */
    static Path tracesDir() {
        var override = System.getProperty("pi-java.traces.dir");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        return Path.of(System.getProperty("user.home"), ".pi-java", "logs", "traces");
    }

    @Override
    public StreamIterator stream(
            ModelId<?> model, Context context, StreamOptions options) {
        // Skip building the request payload entirely when recording is off
        // (the default): serializing the full message list on every LLM call
        // is the hot path and would be pure waste.
        if (telemetry.recordsPayloads()) {
            telemetry.recordEvent(REQUEST, requestPayload(model, context, options));
        }
        return new ResponseRecordingIterator(
            inner.stream(model, context, options), telemetry);
    }

    /** Serialize the request-level payload: model, messages, tools, limits, thinking. */
    private static Map<String, Object> requestPayload(
            ModelId<?> model, Context context, StreamOptions options) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("model", model.provider() + "/" + model.modelName());
        payload.put("systemPrompt", context.systemPrompt());
        payload.put("messages", context.messages().stream()
            .map(PayloadRecordingStreamFn::message).toList());
        payload.put("tools", ToolRegistry.definitionsOf(context.tools()).stream()
            .map(PayloadRecordingStreamFn::tool).toList());
        payload.put("maxTokens", options.maxTokens().isPresent() ? options.maxTokens().getAsInt() : -1);
        payload.put("temperature", options.temperature().isPresent() ? options.temperature().getAsDouble() : -1);
        var thinking = options.thinking();
        payload.put("thinking", thinking != null && thinking.enabled());
        return payload;
    }

    /** Serialize a message as {@code {role, content}} (ContentBlock carries its own type info). */
    private static Map<String, Object> message(Message message) {
        var out = new LinkedHashMap<String, Object>();
        out.put("role", message.role());
        out.put("content", message.content());
        return out;
    }

    /** Serialize a tool definition as {@code {name, inputSchema}} (schema only, no internals). */
    private static Map<String, Object> tool(ToolDefinition tool) {
        var out = new LinkedHashMap<String, Object>();
        out.put("name", tool.name());
        out.put("inputSchema", tool.inputSchema());
        return out;
    }

    /**
     * Passes events through, keeping the latest partial snapshot, and on the
     * terminal event ({@link StreamEvent.StreamDone}/{@link StreamEvent.StreamError})
     * records the final assistant message as the response payload.
     */
    private static final class ResponseRecordingIterator implements StreamIterator {

        private final StreamIterator inner;
        private final TelemetryContext telemetry;
        private AssistantMessage lastPartial;

        private ResponseRecordingIterator(StreamIterator inner, TelemetryContext telemetry) {
            this.inner = inner;
            this.telemetry = telemetry;
        }

        @Override
        public boolean hasNext() {
            return inner.hasNext();
        }

        @Override
        public StreamEvent next() {
            var event = inner.next();
            if (event.partial() != null) {
                lastPartial = event.partial();
            }
            if (event instanceof StreamEvent.StreamDone
                    || event instanceof StreamEvent.StreamError) {
                telemetry.recordEvent(RESPONSE, responsePayload(event));
            }
            return event;
        }

        @Override
        public void close() {
            inner.close();
        }

        private Map<String, Object> responsePayload(StreamEvent terminal) {
            var payload = new LinkedHashMap<String, Object>();
            payload.put("stopReason", terminal.partial() != null
                ? terminal.partial().stopReason() : terminal instanceof StreamEvent.StreamError se
                    ? "error" : null);
            if (terminal instanceof StreamEvent.StreamError se) {
                payload.put("error", String.valueOf(se.error()));
            }
            if (lastPartial != null) {
                // Only id + content + stopReason: content blocks carry their own
                // type info, and the streaming partial's usage field nests another
                // partial (unbounded recursion if serialized verbatim).
                var message = new LinkedHashMap<String, Object>();
                message.put("id", lastPartial.id());
                message.put("content", lastPartial.content());
                message.put("stopReason", lastPartial.stopReason());
                payload.put("message", message);
            }
            return payload;
        }
    }
}
