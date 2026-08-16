package com.pijava.ai.stream;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.pijava.ai.Usage;
import com.pijava.ai.message.AssistantMessage;

/**
 * A streaming event emitted during an LLM response.
 *
 * <p>Phase 2a: 13 event types, each carrying an {@link AssistantMessage}
 * {@code partial} snapshot. Consumers (notably {@code AgentHarness}) use
 * the partial to replace the last assistant message in context without
 * manually accumulating deltas.</p>
 *
 * <h3>Event flow</h3>
 * <pre>
 *   Start → (TextStart → TextDelta* → TextEnd)*
 *         → (ThinkingStart → ThinkingDelta* → ThinkingEnd)*
 *         → (ToolCallStart → ToolCallDelta* → ToolCallEnd)*
 *         → UsageInfo*
 *         → StreamDone | StreamError
 * </pre>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = StreamEvent.Start.class, name = "start"),
    @JsonSubTypes.Type(value = StreamEvent.TextStart.class, name = "text_start"),
    @JsonSubTypes.Type(value = StreamEvent.TextDelta.class, name = "text_delta"),
    @JsonSubTypes.Type(value = StreamEvent.TextEnd.class, name = "text_end"),
    @JsonSubTypes.Type(value = StreamEvent.ThinkingStart.class, name = "thinking_start"),
    @JsonSubTypes.Type(value = StreamEvent.ThinkingDelta.class, name = "thinking_delta"),
    @JsonSubTypes.Type(value = StreamEvent.ThinkingEnd.class, name = "thinking_end"),
    @JsonSubTypes.Type(value = StreamEvent.ToolCallStart.class, name = "toolcall_start"),
    @JsonSubTypes.Type(value = StreamEvent.ToolCallDelta.class, name = "toolcall_delta"),
    @JsonSubTypes.Type(value = StreamEvent.ToolCallEnd.class, name = "toolcall_end"),
    @JsonSubTypes.Type(value = StreamEvent.UsageInfo.class, name = "usage"),
    @JsonSubTypes.Type(value = StreamEvent.StreamDone.class, name = "done"),
    @JsonSubTypes.Type(value = StreamEvent.StreamError.class, name = "error")
})
public sealed interface StreamEvent {

    /**
     * Current snapshot of the assistant message being built.
     * All 13 event types carry this — consumers can simply replace
     * the last message with {@code event.partial()} on every event.
     */
    AssistantMessage partial();

    // ═══════════════════════════════════════════════════════════
    // Lifecycle events
    // ═══════════════════════════════════════════════════════════

    /** Stream has started. Agent loop uses this to initialize the message slot. */
    record Start(AssistantMessage partial) implements StreamEvent {}

    // ═══════════════════════════════════════════════════════════
    // Text block events (text_start → text_delta* → text_end)
    // ═══════════════════════════════════════════════════════════

    /**
     * A text block is starting.
     * @param contentIndex position of this content block within the message
     */
    record TextStart(int contentIndex, AssistantMessage partial) implements StreamEvent {}

    /**
     * A chunk of text content.
     * @param contentIndex position of this content block within the message
     * @param delta the incremental text
     */
    record TextDelta(int contentIndex, String delta, AssistantMessage partial) implements StreamEvent {}

    /**
     * A text block is complete.
     * @param contentIndex position of this content block within the message
     * @param text the full accumulated text
     */
    record TextEnd(int contentIndex, String text, AssistantMessage partial) implements StreamEvent {}

    // ═══════════════════════════════════════════════════════════
    // Thinking block events (thinking_start → thinking_delta* → thinking_end)
    // ═══════════════════════════════════════════════════════════

    /**
     * A thinking block is starting (extended reasoning / chain-of-thought).
     * @param contentIndex position of this content block within the message
     */
    record ThinkingStart(int contentIndex, AssistantMessage partial) implements StreamEvent {}

    /**
     * A chunk of thinking content. Separate from {@link TextDelta} so consumers
     * can handle reasoning content differently from conversational text.
     * @param contentIndex position of this content block within the message
     * @param delta the incremental thinking text
     */
    record ThinkingDelta(int contentIndex, String delta, AssistantMessage partial) implements StreamEvent {}

    /**
     * A thinking block is complete.
     * @param contentIndex position of this content block within the message
     * @param thinking the full accumulated thinking text
     */
    record ThinkingEnd(int contentIndex, String thinking, AssistantMessage partial) implements StreamEvent {}

    // ═══════════════════════════════════════════════════════════
    // Tool call events
    // ═══════════════════════════════════════════════════════════

    /**
     * A tool call is starting.
     * Aligned with pi: {@code toolcall_start} carries only contentIndex.
     * Tool name and ID arrive in {@link ToolCallEnd}.
     * @param contentIndex position of this content block within the message
     */
    record ToolCallStart(int contentIndex, AssistantMessage partial) implements StreamEvent {}

    /**
     * A chunk of JSON arguments for an in-progress tool call.
     * @param contentIndex position of this content block within the message
     * @param id the tool call ID
     * @param jsonDelta incremental JSON fragment
     */
    record ToolCallDelta(int contentIndex, String id, String jsonDelta, AssistantMessage partial) implements StreamEvent {}

    /**
     * A tool call is complete.
     * @param contentIndex position of this content block within the message
     * @param id the tool call ID
     * @param name the tool name
     * @param arguments parsed JSON arguments (defensive copy)
     */
    record ToolCallEnd(int contentIndex, String id, String name, Map<String, Object> arguments, AssistantMessage partial) implements StreamEvent {
        public ToolCallEnd {
            arguments = Map.copyOf(arguments);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Meta events
    // ═══════════════════════════════════════════════════════════

    /**
     * Token usage statistics for the current request.
     *
     * @param usage full usage breakdown (cache/cost), may be null when the
     *              provider only reports input/output counts
     */
    record UsageInfo(long inputTokens, long outputTokens, AssistantMessage partial,
                     Usage usage) implements StreamEvent {

        /** Construct usage info without a full {@link Usage} breakdown. */
        public UsageInfo(long inputTokens, long outputTokens, AssistantMessage partial) {
            this(inputTokens, outputTokens, partial, null);
        }

        /** Build usage info from a full usage breakdown, if available. */
        public static UsageInfo from(long inputTokens, long outputTokens,
                                     AssistantMessage partial, Usage usage) {
            return new UsageInfo(inputTokens, outputTokens, partial, usage);
        }
    }

    /**
     * The stream finished normally.
     * @param reason stop reason: "end_turn", "stop", "tool_use", "length"
     * @param usage final token usage (may be null)
     * @param partial final complete assistant message snapshot
     */
    record StreamDone(String reason, UsageInfo usage, AssistantMessage partial) implements StreamEvent {}

    /**
     * An error occurred during streaming.
     * Aligned with pi: errors are encoded in the stream, not thrown.
     * @param reason discriminator: "aborted" | "error"
     * @param error the underlying exception
     * @param partial best-effort snapshot at the point of error
     */
    record StreamError(String reason, Throwable error, AssistantMessage partial) implements StreamEvent {}
}
