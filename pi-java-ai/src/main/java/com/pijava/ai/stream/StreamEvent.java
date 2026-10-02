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
     * 累加器**尚未落定**时 {@code stopReason} 的占位值 —— pi 五条车道的同一个字面量
     * （{@code anthropic-messages.ts:526}、{@code google-generative-ai.ts:75}、
     * {@code mistral-conversations.ts:222}、{@code openai-completions.ts:333}、
     * {@code openai-responses.ts:139}）。
     *
     * <p><b>它不是 pi 的 {@code StopReason} 词汇表成员</b>：pi 的存盘类型显式排除它
     * （{@code harness/session/types.ts:13} 的 {@code Exclude<StopReason, "pending">}）。
     * 它的含义是「还没有终局判定」⇒ 任何把它当落定取值用的读取点都是错的
     * （{@link #isSettled} 是唯一的判据）。</p>
     */
    String PENDING_STOP_REASON = "pending";

    /**
     * Current snapshot of the assistant message being built.
     * All 13 event types carry this — consumers can simply replace
     * the last message with {@code event.partial()} on every event.
     */
    AssistantMessage partial();

    /**
     * 消息的 {@code stopReason} 是否已是**落定值**（非 null、非空、非
     * {@link #PENDING_STOP_REASON}）。
     *
     * <p>pi 的对应物是一条隐式不变量：终局事件推出的一定是**已经就地写过**
     * {@code output.stopReason} 的那个累加器（{@code anthropic-messages.ts:817-826}）。
     * 终局载荷的消费者可以假定它成立；生产端由 {@code StreamDone.settle} /
     * {@code StreamError.settle} 保证。</p>
     */
    static boolean isSettled(String stopReason) {
        return stopReason != null && !stopReason.isEmpty() && !PENDING_STOP_REASON.equals(stopReason);
    }

    /**
     * 落定后的消息：{@code stopReason} 缺位（null／空／{@code "pending"}）时补上。
     *
     * <p>已经落定的消息<b>原样返回</b> —— pi 的 {@code reason} 只是
     * {@code output.stopReason} 的冗余投影（{@code anthropic-messages.ts:815}），
     * 两者冲突时**消息是权威**。</p>
     */
    private static AssistantMessage settleMessage(
            String reason, AssistantMessage partial, String fallback) {
        var base = partial == null ? AssistantMessage.empty() : partial;
        if (isSettled(base.stopReason())) {
            return base;
        }
        return base.withStopReason(reason == null || reason.isEmpty() ? fallback : reason);
    }

    /** 落定后事件的 {@code reason} 组件：与消息上的落定值**同值**（同一权威）。 */
    private static String settleReason(
            String reason, AssistantMessage settled, String fallback) {
        if (isSettled(settled.stopReason())) {
            return settled.stopReason();
        }
        return reason == null || reason.isEmpty() ? fallback : reason;
    }

    /**
     * {@code Throwable} ⇒ 错误文本：{@code getMessage()}，空则 {@code toString()}
     * —— 与 agent-core 的 {@code RunFailure:90} 同一口径（pi 侧是
     * {@code error instanceof Error ? error.message : JSON.stringify(error)}，
     * 两语言 {@code Error} 同名不同物，该偏差已在 {@code RunFailure} 登记）。
     */
    private static String errorTextOf(Throwable cause) {
        if (cause == null) {
            return null;
        }
        var message = cause.getMessage();
        return message == null || message.isEmpty() ? cause.toString() : message;
    }

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
     *
     * <p>{@link #partial()} <b>就是</b> pi 的 {@code done.message}
     * （{@code ai/src/types.ts:652-668}）—— 终局载荷不是另建的快照，而是
     * **落定后的累加器**：它的 {@code stopReason} 必为终局值（{@link #isSettled}）、
     * {@code usage} 非 null、{@code content} 是整条流累积的块。pi 在车道里就地写这三样
     * （{@code anthropic-messages.ts:815-816}），本仓由 {@link #settle} 这一个工厂写。</p>
     *
     * <p>{@link #reason()} 是 {@code partial.stopReason()} 的**冗余投影**（pi
     * {@code push({type:"done", reason: output.stopReason, message: output})}）⇒
     * 两者必须同值。{@link #usage()} 是事件级的计量副本，允许为 null
     * （消息上的 usage 才是权威，出口缝会兜零）。</p>
     *
     * @param reason stop reason: "stop", "toolUse", "length"（pi {@code StopReason} 的词表；
     *               ⚠️ 不含 "end_turn" —— 那是 Anthropic 线格取值，车道在映射时就翻了，
     *               见 {@code AssistantMessage#stopReason}）
     * @param usage final token usage (may be null)
     * @param partial final complete assistant message snapshot
     */
    record StreamDone(String reason, UsageInfo usage, AssistantMessage partial) implements StreamEvent {

        /**
         * 用一条已落定的消息造终局（旁路生产者的唯一入口，见 {@code docs/55 §6.2}）。
         *
         * <p>只做「在已有消息上补缺」：不新建消息、不重组内容 ⇒ 无论谁先落定，
         * 结果同值。落定值取 {@code partial.stopReason()}（已落定时）或 {@code reason}
         * （缺省 {@code "stop"}）。</p>
         *
         * @param reason  车道给的停因；空则用消息上的落定值，再空则 {@code "stop"}
         * @param partial 终局快照；null 视作空快照
         */
        public static StreamDone settle(String reason, AssistantMessage partial) {
            var message = StreamEvent.settleMessage(reason, partial, "stop");
            return new StreamDone(StreamEvent.settleReason(reason, message, "stop"), null, message);
        }
    }

    /**
     * An error occurred during streaming.
     * Aligned with pi: errors are encoded in the stream, not thrown.
     *
     * <p>{@link #partial()} <b>就是</b> pi 的 {@code error.error}
     * （{@code ai/src/types.ts:652-668}）：除了 {@code done} 那三样，错误路还保证
     * {@code errorMessage} 有文本（来自 {@code Throwable}），而 {@code content}
     * **保留流到故障点为止的块** —— pi 的实测行为（{@code docs/55 §3.1} P3/P4）。</p>
     *
     * <p>{@link #error()} 这个 {@code Throwable} 组件在 pi **没有对应物**（pi 的错误
     * 文本就在消息上）⇒ 它只留给日志与 Java 侧栈；<b>下游不许再从它反推文本</b>，
     * 要文本就用 {@link #textOf(StreamError)}（登记 B114）。</p>
     *
     * @param reason discriminator: "aborted" | "error"
     * @param error the underlying exception（仅日志用，可 null）
     * @param partial settled snapshot at the point of error
     */
    record StreamError(String reason, Throwable error, AssistantMessage partial)
            implements StreamEvent {

        /**
         * 用一条已落定的消息造终局（旁路生产者的唯一入口，见 {@code docs/55 §6.2}）。
         *
         * <p>补两样：{@code stopReason}（缺省 {@code "error"}）与 {@code errorMessage}
         * （取 {@code cause} 的文本；消息上已有文本则不动）。内容与身份原样保留。</p>
         *
         * @param reason  车道给的停因；空则取 {@code "error"}
         * @param cause   底层异常；文本的唯一来源，可 null（此时 {@code errorMessage} 缺席）
         * @param partial 终局快照；null 视作空快照
         */
        public static StreamError settle(String reason, Throwable cause, AssistantMessage partial) {
            var message = StreamEvent.settleMessage(reason, partial, "error");
            var text = StreamEvent.errorTextOf(cause);
            if (text != null
                    && (message.errorMessage() == null || message.errorMessage().isEmpty())) {
                message = message.withErrorMessage(text);
            }
            return new StreamError(StreamEvent.settleReason(reason, message, "error"), cause, message);
        }

        /**
         * 终局错误文本：**消息优先**（{@code partial.errorMessage()}），
         * {@code Throwable} 仅兜底（未经 {@link #settle} 的裸事件、旧夹具、conformance 桩）。
         *
         * <p>这是全仓读错误文本的唯一入口 ⇒ 「谁有能力说这条流错在哪」从 6 处降到 1 处
         * （{@code docs/55 §7 R3}）。两侧皆空时返回 null，由调用方决定兜底文案。</p>
         */
        public static String textOf(StreamError event) {
            if (event == null) {
                return null;
            }
            var partial = event.partial();
            if (partial != null && partial.errorMessage() != null
                    && !partial.errorMessage().isEmpty()) {
                return partial.errorMessage();
            }
            return StreamEvent.errorTextOf(event.error());
        }
    }
}

