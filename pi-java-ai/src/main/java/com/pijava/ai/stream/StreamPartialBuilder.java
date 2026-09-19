package com.pijava.ai.stream;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;

/**
 * Mutable builder that accumulates stream events into {@link AssistantMessage}
 * snapshots, one per event.
 *
 * <p>Protocol adapters use this to produce {@link StreamEvent}s with correct
 * {@code partial} values. Each {@code emit*()} method mutates internal state
 * and returns the corresponding event carrying the current snapshot.</p>
 *
 * <p>Thread-safe for single-threaded streaming use.</p>
 */
public final class StreamPartialBuilder {

    private final String messageId;
    private final List<ContentBlock> blocks = new ArrayList<>();
    private StreamEvent.UsageInfo usage;
    /**
     * 累加器的 stop reason —— **初值 {@code "pending"}**，与 pi 五条车道同形
     * （{@code anthropic-messages.ts:526}、{@code google-generative-ai.ts:75}、
     * {@code mistral-conversations.ts:222}、{@code openai-completions.ts:333}、
     * {@code openai-responses.ts:139}），**只有终局事件改写它**。
     *
     * <p>⑩（B26）：故流进行中**每一帧**（{@code message_start} 与每个
     * {@code message_update}）的载荷里它都是 {@code "pending"}；终局事件把它换成
     * 车道的映射结果。<b>它不是 pi 的 {@code StopReason} 词汇表成员</b> ——
     * pi 的**存盘**类型显式排除它（{@code harness/session/types.ts:13} 的
     * {@code Exclude<StopReason, "pending">}，conformance
     * {@code session/testing/conformance/session-repo.ts:264} 拿
     * 「append 一条 pending」当反例）⇒ 它的含义是「还没有终局判定」，任何把它
     * 当作落定取值的读取点都是错的（宿主侧由 {@code PiLoopRunner.markAborted}
     * 显式折算，见该处注释）。</p>
     */
    private String stopReason = "pending";
    /**
     * 线格**原值**（pi {@code output.rawStopReason}，{@code types.ts:443}）—— 与
     * {@link #stopReason} 的映射结果分开存。⑨（D5）：五条车道都在观测到线格取值的那一刻
     * 经 {@link #noteRawStopReason} 写入，故此后**每一帧** partial 都快照到它（pi 写的是
     * 同一个可变对象，形状相同）。
     */
    private String rawStopReason;

    // Per-block accumulators
    private final StringBuilder textBuf = new StringBuilder();
    private final StringBuilder thinkingBuf = new StringBuilder();
    private final StringBuilder thinkingSigBuf = new StringBuilder();
    private final StringBuilder toolArgBuf = new StringBuilder();
    private String toolCallId = "";
    private String toolCallName = "";
    // Redacted flag of the *current* thinking block. Carried on every block
    // rewrite so a later signature/delta cannot silently clear it.
    private boolean thinkingRedacted;

    private int nextContentIndex;
    // Each stream owns its block index so interleaved text/thinking/tool
    // deltas never overwrite each other's block (they used to target
    // blocks.size()-1, which corrupted the snapshot when streams alternated).
    private int textBlockIndex = -1;
    private int thinkingBlockIndex = -1;
    private int toolBlockIndex = -1;

    /**
     * Create a builder for a message with the given ID.
     *
     * @param messageId the message identifier
     */
    public StreamPartialBuilder(String messageId) {
        this.messageId = messageId;
    }

    /** Create a builder for a message with a randomly generated ID. */
    public StreamPartialBuilder() {
        this(java.util.UUID.randomUUID().toString());
    }

    // ── Snapshot ─────────────────────────────────────────────

    /**
     * Return the current {@link AssistantMessage} snapshot.
     *
     * <p>⚠️ 走**全参**构造器而不是 4 参兼容构造器：兼容构造器把后 6 个可选字段一并置 null
     * （它服务的是「旧形状」），会静默丢掉 {@link #rawStopReason}。身份四元与本字段由
     * {@code AbstractChatApi} 在事件出口经 wither 挂载／携带（3a、⑨）。</p>
     */
    public AssistantMessage snapshot() {
        return new AssistantMessage(messageId, List.copyOf(blocks), usage, stopReason,
            null, null, null, null, null, rawStopReason);
    }

    /**
     * 记下**线格原值**（pi {@code output.rawStopReason} 的就地赋值）。
     *
     * <p>⑨（D5）：五条车道的调用点与 pi 的写点**逐处对应**（{@code anthropic:744}、
     * {@code google:217}、{@code mistral:614}、{@code completions:572}、
     * {@code responses-shared:588}/{@code :747}）。传 null 即 pi 的赋 {@code undefined}
     * （收尾处 {@code status} 缺席时就是这一支）⇒ 快照上键缺席，与 pi 同形。</p>
     */
    public void noteRawStopReason(String raw) {
        this.rawStopReason = raw;
    }

    /** Return the current content index (next slot). */
    public int contentIndex() {
        return nextContentIndex;
    }

    /** Current usage info, or null. */
    public StreamEvent.UsageInfo usage() {
        return usage;
    }

    // ═══════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════

    /** Emit the stream-start event. */
    public StreamEvent.Start emitStart() {
        return new StreamEvent.Start(snapshot());
    }

    // ═══════════════════════════════════════════════════════════
    // Text block
    // ═══════════════════════════════════════════════════════════

    /** Emit text-block-start. Adds a placeholder {@link ContentBlock.TextContent}. */
    public StreamEvent.TextStart emitTextStart() {
        textBuf.setLength(0);
        textBlockIndex = blocks.size();
        blocks.add(new ContentBlock.TextContent(""));
        int idx = nextContentIndex++;
        return new StreamEvent.TextStart(idx, snapshot());
    }

    /** Emit a text delta. Updates the current text block in-place. */
    public StreamEvent.TextDelta emitTextDelta(String delta) {
        textBuf.append(delta);
        if (textBlockIndex < 0) {
            // A text delta without a preceding start: create the block lazily.
            textBlockIndex = blocks.size();
            blocks.add(new ContentBlock.TextContent(""));
            nextContentIndex++;
        }
        int idx = textBlockIndex;
        blocks.set(idx, new ContentBlock.TextContent(textBuf.toString()));
        return new StreamEvent.TextDelta(idx, delta, snapshot());
    }

    /** Emit text-block-end. The text block is already finalized. */
    public StreamEvent.TextEnd emitTextEnd() {
        int idx = Math.max(0, textBlockIndex);
        return new StreamEvent.TextEnd(idx, textBuf.toString(), snapshot());
    }

    // ═══════════════════════════════════════════════════════════
    // Thinking block
    // ═══════════════════════════════════════════════════════════

    /** Emit thinking-block-start. Adds a placeholder {@link ContentBlock.ThinkingContent}. */
    public StreamEvent.ThinkingStart emitThinkingStart() {
        return emitThinkingStart("", "", false);
    }

    /**
     * Emit thinking-block-start carrying the provider's pre-set content.
     *
     * <p>Anthropic puts text and signature inside {@code content_block_start} and
     * pi builds the block with them <b>before</b> pushing the start event
     * ({@code anthropic-messages.ts:630-635} build, {@code :636} into
     * {@code output.content}, {@code :637} push) — so pi's first {@code partial}
     * already sees both. Same for {@code redacted_thinking} ({@code :638-647}).</p>
     *
     * <p><b>The buffers must be seeded, not just the block</b>: every later
     * {@code emitThinkingDelta} rebuilds the block from {@code thinkingBuf}, so a
     * block-only initial value would be wiped by the first delta.</p>
     *
     * @param initialText      pre-set reasoning text, or null/empty
     * @param initialSignature pre-set signature (the opaque payload when redacted)
     * @param redacted         provider redaction marker
     */
    public StreamEvent.ThinkingStart emitThinkingStart(
            String initialText, String initialSignature, boolean redacted) {
        var text = initialText == null ? "" : initialText;
        var sig = initialSignature == null ? "" : initialSignature;
        thinkingBuf.setLength(0);
        thinkingBuf.append(text);
        thinkingSigBuf.setLength(0);
        thinkingSigBuf.append(sig);
        thinkingRedacted = redacted;
        thinkingBlockIndex = blocks.size();
        blocks.add(new ContentBlock.ThinkingContent(text, sig, redacted));
        int idx = nextContentIndex++;
        return new StreamEvent.ThinkingStart(idx, snapshot());
    }

    /** Emit a thinking delta. Updates the current thinking block in-place. */
    public StreamEvent.ThinkingDelta emitThinkingDelta(String delta) {
        thinkingBuf.append(delta);
        if (thinkingBlockIndex < 0) {
            thinkingBlockIndex = blocks.size();
            blocks.add(new ContentBlock.ThinkingContent(""));
            nextContentIndex++;
        }
        int idx = thinkingBlockIndex;
        blocks.set(idx, new ContentBlock.ThinkingContent(
            thinkingBuf.toString(), thinkingSigBuf.toString(), thinkingRedacted));
        return new StreamEvent.ThinkingDelta(idx, delta, snapshot());
    }

    /** Emit a signature delta for the current thinking block (Anthropic). */
    public StreamEvent.ThinkingDelta emitThinkingSignature(String signature) {
        thinkingSigBuf.append(signature);
        int idx = Math.max(0, thinkingBlockIndex);
        blocks.set(idx, new ContentBlock.ThinkingContent(
            thinkingBuf.toString(), thinkingSigBuf.toString(), thinkingRedacted));
        return new StreamEvent.ThinkingDelta(idx, "", snapshot());
    }

    /**
     * Write the provider's final signature/redacted flags onto the current
     * thinking block <b>without emitting an event</b>.
     *
     * <p>pi's {@code pi-messages} lane does exactly this: the wire's
     * {@code thinking_end} carries {@code contentSignature}/{@code redacted}
     * ({@code pi-messages.ts:60-64}, assigned at {@code :236-240}) — there is no
     * separate signature event to emit.</p>
     *
     * @param signature provider signature, or null/empty
     * @param redacted  provider redaction marker
     */
    public void applyThinkingSignature(String signature, boolean redacted) {
        thinkingSigBuf.setLength(0);
        thinkingSigBuf.append(signature == null ? "" : signature);
        thinkingRedacted = redacted;
        if (thinkingBlockIndex >= 0) {
            blocks.set(thinkingBlockIndex, new ContentBlock.ThinkingContent(
                thinkingBuf.toString(), thinkingSigBuf.toString(), thinkingRedacted));
        }
    }

    /** Emit thinking-block-end. */
    public StreamEvent.ThinkingEnd emitThinkingEnd() {
        int idx = Math.max(0, thinkingBlockIndex);
        return new StreamEvent.ThinkingEnd(idx, thinkingBuf.toString(), snapshot());
    }

    // ═══════════════════════════════════════════════════════════
    // Tool call block
    // ═══════════════════════════════════════════════════════════

    /**
     * Emit tool-call-start carrying the call's identity.
     *
     * <p>pi 的五条车道都在发出 {@code toolcall_start} <b>之前</b>先把块建好、塞进
     * {@code output.content}，再 push 起点事件（{@code anthropic-messages.ts:648-660}、
     * {@code openai-completions.ts:497-536}）⇒ pi 的起点 {@code partial} 里那个位置
     * <b>已经是</b>带 {@code id}/{@code name} 的 toolCall 块。本方法同序：<b>先入
     * {@code blocks} 再取快照</b>。</p>
     *
     * <p><b>签名不留无参重载</b>：六个调用点在起点都拿得到 id/name（至少其一），
     * 留一条无参的路就是留一条「空身份」的路。</p>
     *
     * <p>⚠️ 顺带修好一条隐性偏差：{@link #emitToolCallDelta} 从
     * {@link #toolCallId}/{@link #toolCallName} 重建块，而 {@code toolCallName}
     * 此前<b>只有 {@link #emitToolCallEnd} 才写</b> ⇒ 整个参数流期间块上的 name
     * 恒为空串。此处 seed 之后，参数流全程携带工具名。</p>
     *
     * @param id   provider 的调用 ID；null 视作空串
     * @param name 工具名；null 视作空串（起点 name 为空是 pi 自己也有的形状，
     *             见 {@code openai-completions.ts:534-536} 的「稍后就地补」）
     */
    public StreamEvent.ToolCallStart emitToolCallStart(String id, String name) {
        toolArgBuf.setLength(0);
        toolCallId = id == null ? "" : id;
        toolCallName = name == null ? "" : name;
        toolBlockIndex = blocks.size();
        blocks.add(new ContentBlock.ToolUseContent(toolCallId, toolCallName, Map.of()));
        int idx = nextContentIndex++;
        return new StreamEvent.ToolCallStart(idx, snapshot());
    }

    /** Emit a tool-call argument delta. */
    public StreamEvent.ToolCallDelta emitToolCallDelta(String id, String jsonDelta) {
        this.toolCallId = id;
        toolArgBuf.append(jsonDelta);
        if (toolBlockIndex < 0) {
            toolBlockIndex = blocks.size();
            blocks.add(new ContentBlock.ToolUseContent("", "", Map.of()));
            nextContentIndex++;
        }
        int idx = toolBlockIndex;
        parseAndSetToolBlock(idx);
        return new StreamEvent.ToolCallDelta(idx, id, jsonDelta, snapshot());
    }

    /** Emit tool-call-end with the full tool name, ID, and parsed arguments. */
    public StreamEvent.ToolCallEnd emitToolCallEnd(String id, String name) {
        this.toolCallId = id;
        this.toolCallName = name;
        int idx = Math.max(0, toolBlockIndex);
        Map<String, Object> args = parseArgs();
        blocks.set(idx, new ContentBlock.ToolUseContent(id, name, args));
        return new StreamEvent.ToolCallEnd(idx, id, name, args, snapshot());
    }

    // ═══════════════════════════════════════════════════════════
    // Meta events
    // ═══════════════════════════════════════════════════════════

    /** Emit usage info. */
    public StreamEvent.UsageInfo emitUsage(long inputTokens, long outputTokens) {
        // Assign before snapshot() so the emitted event's partial carries the
        // usage — consumers (ActionExecutor's token counter) only accept
        // UsageInfo whose partial().usage() is non-null.
        var usageInfo = new StreamEvent.UsageInfo(inputTokens, outputTokens, null);
        this.usage = usageInfo;
        return new StreamEvent.UsageInfo(inputTokens, outputTokens, snapshot());
    }

    /** Emit stream-done. */
    public StreamEvent.StreamDone emitDone(String reason) {
        this.stopReason = reason;
        return new StreamEvent.StreamDone(reason, usage, snapshot());
    }

    /** Emit stream-error. */
    public StreamEvent.StreamError emitError(String reason, Throwable error) {
        this.stopReason = reason;
        return new StreamEvent.StreamError(reason, error, snapshot());
    }

    // ── Helpers ──────────────────────────────────────────────

    @SuppressWarnings("unchecked") // Jackson ObjectMapper.readValue with generic Map type
    private Map<String, Object> parseArgs() {
        try {
            return (Map<String, Object>) (Map<?, ?>) lenientMapper()
                    .readValue(toolArgBuf.toString(), Map.class);
        } catch (Exception e) {
            return Map.of("_raw", toolArgBuf.toString());
        }
    }

    /** ObjectMapper tolerant of common model-output JSON quirks. */
    static com.fasterxml.jackson.databind.ObjectMapper lenientMapper() {
        return new com.fasterxml.jackson.databind.ObjectMapper()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_COMMENTS)
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_SINGLE_QUOTES)
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES)
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_TRAILING_COMMA);
    }

    private void parseAndSetToolBlock(int idx) {
        Map<String, Object> args = parseArgs();
        blocks.set(idx, new ContentBlock.ToolUseContent(toolCallId, toolCallName, args));
    }
}
