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

    /**
     * 用新的 partial 快照替换事件的 {@code partial}（其余字段原样保留）。
     * 3a 的身份挂载点在 {@code AbstractChatApi} 出口统一调它，把
     * api/provider/model/timestamp 写进每个事件携带的快照（pi 的 partial
     * 本就与终局同形状携带这些字段）。{@link UsageInfo} 允许不带快照
     * （{@code partial() == null}），这种情况原样返回。
     */
    static StreamEvent withPartial(StreamEvent event, AssistantMessage newPartial) {
        return switch (event) {
            case Start s -> new Start(newPartial);
            case TextStart s -> new TextStart(s.contentIndex(), newPartial);
            case TextDelta s -> new TextDelta(s.contentIndex(), s.delta(), newPartial);
            case TextEnd s -> new TextEnd(s.contentIndex(), s.text(), newPartial);
            case ThinkingStart s -> new ThinkingStart(s.contentIndex(), newPartial);
            case ThinkingDelta s -> new ThinkingDelta(s.contentIndex(), s.delta(), newPartial);
            case ThinkingEnd s -> new ThinkingEnd(s.contentIndex(), s.thinking(), newPartial);
            case ToolCallStart s -> new ToolCallStart(s.contentIndex(), newPartial);
            case ToolCallDelta s -> new ToolCallDelta(
                s.contentIndex(), s.id(), s.jsonDelta(), newPartial);
            case ToolCallEnd s -> new ToolCallEnd(
                s.contentIndex(), s.id(), s.name(), s.arguments(), newPartial);
            case UsageInfo s -> s.partial() == null ? s
                : new UsageInfo(s.inputTokens(), s.outputTokens(), newPartial, s.usage());
            case StreamDone s -> new StreamDone(s.reason(), s.usage(), newPartial);
            case StreamError s -> new StreamError(s.reason(), s.error(), newPartial);
        };
    }

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
    record ToolCallDelta(int contentIndex, String id, String jsonDelta,
                         AssistantMessage partial) implements StreamEvent {}

    /**
     * A tool call is complete.
     * @param contentIndex position of this content block within the message
     * @param id the tool call ID
     * @param name the tool name
     * @param arguments parsed JSON arguments (defensive copy)
     */
    record ToolCallEnd(int contentIndex, String id, String name,
                       Map<String, Object> arguments, AssistantMessage partial) implements StreamEvent {
        /** Compact constructor that defensively copies the arguments map. */
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

        /**
         * 归一为领域类型 {@link Usage}（包⑥ 裁决 B）。
         *
         * <p>有全量分解用全量（含 cache/cost）；否则按 input/output 合成 ——
         * {@link Usage#of(double, double)} 给出 {@code totalTokens = input + output}
         * 与零 {@code cost}，与 pi 流起点那个初值对象同形（{@code anthropic-messages.ts:518-525}、
         * {@code openai-completions.ts:325-332}）；{@code cacheWrite1h}/{@code reasoning}
         * 缺席，被 {@code @JsonInclude(NON_NULL)} 省略，pi 同。</p>
         *
         * <p>与 {@code Message.AssistantMessage.usageOf} 的分工：那个服务<b>终局消息</b>
         * （{@code UsageInfo == null} ⇒ 返回 null ⇒ 键省略，见登记 B41），本方法服务
         * <b>每一帧</b>的线格式；调用方负责「无 UsageInfo 时兜零值对象」。</p>
         */
        public Usage toUsage() {
            return usage != null ? usage : Usage.of(inputTokens, outputTokens);
        }
    }

    /**
     * The stream finished normally.
     * @param reason stop reason: "stop", "tool_use", "length"（pi {@code StopReason} 的词表；
     *               ⚠️ 不含 "end_turn" —— 那是 Anthropic 线格取值，车道在映射时就翻了，
     *               见 {@code AssistantMessage#stopReason}）
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
