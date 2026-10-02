package com.pijava.ai.stream;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.pijava.ai.Usage;
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
     * 显式折算，见该处注释）。判据与字面量见
     * {@link StreamEvent#PENDING_STOP_REASON}／{@link StreamEvent#isSettled}。</p>
     */
    private String stopReason = StreamEvent.PENDING_STOP_REASON;
    /**
     * 线格**原值**（pi {@code output.rawStopReason}，{@code types.ts:443}）—— 与
     * {@link #stopReason} 的映射结果分开存。⑨（D5）：五条车道都在观测到线格取值的那一刻
     * 经 {@link #noteRawStopReason} 写入，故此后**每一帧** partial 都快照到它（pi 写的是
     * 同一个可变对象，形状相同）。
     */
    private String rawStopReason;

    // Per-block accumulators
    private final StringBuilder textBuf = new StringBuilder();
    // Signature riding on the current text block (Google text parts). Empty
    // buffer ⇒ null on the block (pi never persists an empty signature key).
    private final StringBuilder textSigBuf = new StringBuilder();
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

    /** Index of the current thinking block (for terminal backfill bookkeeping). */
    public int thinkingBlockIndex() {
        return Math.max(0, thinkingBlockIndex);
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
        textSigBuf.setLength(0);
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
        blocks.set(idx, new ContentBlock.TextContent(
            textBuf.toString(), textSignatureOrNull()));
        return new StreamEvent.TextDelta(idx, delta, snapshot());
    }

    private String textSignatureOrNull() {
        return textSigBuf.length() == 0 ? null : textSigBuf.toString();
    }

    /**
     * Retain a thought signature onto the current text block during streaming
     * (pi {@code retainThoughtSignature}, {@code google-shared.ts:139-143}).
     *
     * <p>Some backends send the signature only on the first delta for a part;
     * a non-empty incoming signature overwrites, an empty/null one does not
     * erase the stored signature. This does not move signatures across parts.</p>
     */
    public void retainTextSignature(String signature) {
        if (signature != null && !signature.isEmpty()) {
            textSigBuf.setLength(0);
            textSigBuf.append(signature);
            if (textBlockIndex >= 0) {
                blocks.set(textBlockIndex, new ContentBlock.TextContent(
                    textBuf.toString(), textSigBuf.toString()));
            }
        }
    }

    /**
     * Retain a thought signature onto the current thinking block during
     * streaming (same pi function, {@code google-generative-ai.ts:149-152}).
     * Non-empty incoming overwrites; empty/null does not erase.
     */
    public void retainThinkingSignature(String signature) {
        if (signature != null && !signature.isEmpty()) {
            thinkingSigBuf.setLength(0);
            thinkingSigBuf.append(signature);
            if (thinkingBlockIndex >= 0) {
                blocks.set(thinkingBlockIndex, new ContentBlock.ThinkingContent(
                    thinkingBuf.toString(), thinkingSigBuf.toString(), thinkingRedacted));
            }
        }
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

    /**
     * Stamp the finalized Responses reasoning item: authoritative visible
     * text plus the whole item serialized as the block signature
     * (pi {@code openai-responses-shared.ts:686-697}).
     *
     * @param text     joined summary text (fallback joined content text), or
     *                 {@code null} to keep the text accumulated from deltas
     * @param itemJson {@code JSON.stringify} of the whole reasoning item
     */
    public void stampReasoning(String text, String itemJson) {
        if (text != null) {
            thinkingBuf.setLength(0);
            thinkingBuf.append(text);
        }
        thinkingSigBuf.setLength(0);
        thinkingSigBuf.append(itemJson == null ? "" : itemJson);
        thinkingRedacted = false;
        if (thinkingBlockIndex >= 0) {
            blocks.set(thinkingBlockIndex, new ContentBlock.ThinkingContent(
                thinkingBuf.toString(), thinkingSigBuf.toString(), false));
        }
    }

    /**
     * Restamp the signature on the thinking block at the given index,
     * without touching the text buffers (Azure backfill, pi {@code :533-549}).
     */
    public void restampBlockSignature(int blockIndex, String signature) {
        if (blockIndex < 0 || blockIndex >= blocks.size()) {
            return;
        }
        if (blocks.get(blockIndex) instanceof ContentBlock.ThinkingContent th) {
            blocks.set(blockIndex, new ContentBlock.ThinkingContent(
                th.text(), signature, th.redacted()));
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
        blocks.set(idx, new ContentBlock.ToolUseContent(
            toolCallId, toolCallName, ToolArgumentParser.parse(toolArgBuf.toString())));
        return new StreamEvent.ToolCallDelta(idx, id, jsonDelta, snapshot());
    }

    /** Emit tool-call-end with the full tool name, ID, and parsed arguments. */
    public StreamEvent.ToolCallEnd emitToolCallEnd(String id, String name) {
        return emitToolCallEnd(id, name, null);
    }

    /**
     * Emit tool-call-end carrying the part's thought signature
     * (pi {@code ...(part.thoughtSignature && { thoughtSignature })}，
     * {@code google-generative-ai.ts:201-207}).
     *
     * @param thoughtSignature base64 signature attached to the functionCall
     *        part, or null when absent
     */
    public StreamEvent.ToolCallEnd emitToolCallEnd(
            String id, String name, String thoughtSignature) {
        this.toolCallId = id;
        this.toolCallName = name;
        int idx = Math.max(0, toolBlockIndex);
        Map<String, Object> args = ToolArgumentParser.parse(toolArgBuf.toString());
        blocks.set(idx, new ContentBlock.ToolUseContent(id, name, args, thoughtSignature));
        return new StreamEvent.ToolCallEnd(idx, id, name, args, snapshot());
    }

    // ═══════════════════════════════════════════════════════════
    // Meta events
    // ═══════════════════════════════════════════════════════════

    /** Emit usage carrying only input/output counts; delegates to {@link #emitUsage(Usage)}. */
    public StreamEvent.UsageInfo emitUsage(long inputTokens, long outputTokens) {
        return emitUsage(Usage.of(inputTokens, outputTokens));
    }

    /**
     * Emit usage info carrying the provider's full breakdown (cache/cost/reasoning).
     *
     * <p>包 H1 步 2（{@code docs/42 §8.2}）：这是加宽后的入口。此前 {@code emitUsage}
     * 只收两个 {@code long}，cache/cost/reasoning 全无入口 ⇒ 四分量在生产上恒为 0。
     * 现在全量分解同时进入两条通道：事件自身的 {@code usage()} 与 partial 上的
     * {@code usage()}（后者是 {@code JsonEventMapper} 写线格式时读的那个）。</p>
     *
     * <p>计数通道（{@link StreamEvent.UsageInfo#inputTokens()}）仍从全量分解派生 ——
     * {@code PiLaneSink} 与 {@code SessionRunner} 的累加器读的是它。</p>
     *
     * @param usage the full usage breakdown
     * @return the emitted usage event
     */
    public StreamEvent.UsageInfo emitUsage(Usage usage) {
        // Assign before snapshot() so the emitted event's partial carries the
        // usage — consumers (ActionExecutor's token counter) only accept
        // UsageInfo whose partial().usage() is non-null.
        var usageInfo = usageInfoOf(usage);
        this.usage = usageInfo;
        return new StreamEvent.UsageInfo(usageInfo.inputTokens(), usageInfo.outputTokens(),
            snapshot(), usage);
    }

    /**
     * Record usage <b>without</b> emitting an event.
     *
     * <p>pi mutates {@code output.usage} in place, so a value captured mid-stream is
     * observable on every later partial with no extra event. Anthropic's
     * {@code message_start} capture is exactly that ({@code anthropic-messages.ts:615-625},
     * comment: <i>"This ensures we have input token counts even if the stream is aborted
     * early"</i>). Using {@link #emitUsage(Usage)} there would add a frame pi does not
     * have; this method keeps the frame count identical while still putting the usage
     * on the partial.</p>
     *
     * @param usage the full usage breakdown captured so far
     */
    public void noteUsage(Usage usage) {
        this.usage = usageInfoOf(usage);
    }

    private static StreamEvent.UsageInfo usageInfoOf(Usage usage) {
        return new StreamEvent.UsageInfo(
            (long) usage.input(), (long) usage.output(), null, usage);
    }

    /** Emit stream-done. */
    public StreamEvent.StreamDone emitDone(String reason) {
        this.stopReason = reason;
        return new StreamEvent.StreamDone(reason, usage, snapshot());
    }

    /**
     * Emit stream-error, **settling the accumulator** before pushing it.
     *
     * <p>pi 的失败路是就地落定同一个对象：先删块的流式草稿字段，再
     * {@code output.stopReason = aborted ? "aborted" : "error"}、
     * {@code output.errorMessage = error.message}，最后
     * {@code push({type:"error", reason, error: output})}
     * （{@code anthropic-messages.ts:817-826}）。此前本方法只写 {@code stopReason}
     * ⇒ 错误文本只能由下游各自从 {@code Throwable} 反推（{@code docs/55 §5 F1/F2}）。</p>
     *
     * <p>内容（{@link #snapshot()} 的 blocks）本来就保留 —— 那是 pi 的实测行为
     * （{@code docs/55 §3.1} P4：中止时 {@code error.content} 是流到中止点的文本）。</p>
     */
    public StreamEvent.StreamError emitError(String reason, Throwable error) {
        var settled = StreamEvent.StreamError.settle(reason, error, snapshot());
        // 让 builder 自身的状态与事件一致（快照上已落定，字段不能还停在旧值）。
        this.stopReason = settled.partial().stopReason();
        return settled;
    }

}
