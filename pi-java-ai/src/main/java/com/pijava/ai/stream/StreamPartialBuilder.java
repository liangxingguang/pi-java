package com.pijava.ai.stream;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.pijava.ai.Usage;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.AssistantMessageDiagnostic;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.StopReason;

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
    // 包级可见：text/thinking 两簇的实现在 TextThinkingBlockAccumulator，
    // 与 tool 簇共享同一份 blocks/nextContentIndex（结构债拆分——纯移动零行为变化）。
    final List<ContentBlock> blocks = new ArrayList<>();
    private StreamEvent.UsageInfo usage;
    /**
     * 累加器的 stop reason —— **初值 {@code "pending"}**，与 pi 五条车道同形
     * （{@code anthropic-messages.ts:526}、{@code google-generative-ai.ts:75}、
     * {@code mistral-conversations.ts:222}、{@code openai-completions.ts:333}、
     * {@code openai-responses.ts:139}），**只有终局事件改写它**。
     *
     * <p>⑩（B26）：故流进行中**每一帧**（{@code message_start} 与每个
     * {@code message_update}）的载荷里它都是 {@link StopReason#PENDING}；终局事件把它换成
     * 车道的映射结果。<b>pi 的**存盘**类型显式排除 PENDING</b>
     * （{@code harness/session/types.ts:13} 的 {@code Exclude<StopReason, "pending">}，
     * conformance {@code session/testing/conformance/session-repo.ts:264} 拿
     * 「append 一条 pending」当反例）⇒ 它的含义是「还没有终局判定」，任何把它
     * 当作落定取值的读取点都是错的（宿主侧由 {@code PiLoopRunner.markAborted}
     * 显式折算，见该处注释）。判据见 {@link StopReason#isSettled}。</p>
     */
    StopReason stopReason = StopReason.PENDING;
    /**
     * 线格**原值**（pi {@code output.rawStopReason}，{@code types.ts:443}）—— 与
     * {@link #stopReason} 的映射结果分开存。⑨（D5）：五条车道都在观测到线格取值的那一刻
     * 经 {@link #noteRawStopReason} 写入，故此后**每一帧** partial 都快照到它（pi 写的是
     * 同一个可变对象，形状相同）。
     */
    private String rawStopReason;
    /**
     * B110：provider-native effort（pi {@code output.providerThinkingLevel}）。
     * 默认 null；由车道（anthropic managed ／ pi-messages 透传）经
     * {@link #noteProviderThinkingLevel} 写入。
     */
    private String providerThinkingLevel;

    /**
     * B148：provider 实际服务的模型 id（pi {@code output.responseModel}）。
     * 由车道在观测到线格 model 与请求 id 不同时写入；<b>首值胜</b>
     * （completions 的 {@code output.responseModel ||= chunk.model}）。
     */
    private String responseModel;

    /**
     * B148：provider/运行时诊断（pi {@code output.diagnostics}）。
     * 默认 null（≙ undefined，键省略）；{@link #appendDiagnostic} 按需建表追加。
     */
    private List<AssistantMessageDiagnostic> diagnostics;

    // Per-block accumulators（text/thinking 在 TextThinkingBlockAccumulator；
    // tool 簇在 ToolCallBlockAccumulator——批 30 拆分）。
    private final TextThinkingBlockAccumulator textThinking = new TextThinkingBlockAccumulator(this);
    private final ToolCallBlockAccumulator toolCalls = new ToolCallBlockAccumulator(this);

    int nextContentIndex;

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
            null, null, null, responseModel, providerThinkingLevel,
            diagnostics == null ? null : List.copyOf(diagnostics),
            null, null, rawStopReason, null);
    }

    /**
     * B110：盖上 provider-native effort（pi {@code output.providerThinkingLevel}）。
     * 一旦记下，后续每个快照都携带（与 rawStopReason 同形）；传 null 不出键。
     * pi-messages 终局帧透传在 emitDone/emitError 之前调它。
     */
    public void noteProviderThinkingLevel(String level) {
        this.providerThinkingLevel = level;
    }

    /**
     * B148：记下 provider 实际服务的模型 id（pi {@code output.responseModel}）。
     *
     * <p><b>首值胜</b>：对位 completions 车道的
     * {@code output.responseModel ||= chunk.model}（openai-completions.ts:566）——
     * 已设非空值时后续 chunk 不覆盖；传 null/空白忽略。anthropic 车道一条流只有
     * 一次 message_start，同函数收口。</p>
     */
    public void noteResponseModel(String model) {
        if (this.responseModel == null && model != null && !model.isEmpty()) {
            this.responseModel = model;
        }
    }

    /**
     * B148：追加一条诊断（pi {@code appendAssistantMessageDiagnostic}，
     * diagnostics.ts:42-46）。每次追加生成不可变新表，快照持当前拷贝。
     */
    public void appendDiagnostic(AssistantMessageDiagnostic diagnostic) {
        var copy = new ArrayList<AssistantMessageDiagnostic>(
            diagnostics == null ? List.of() : diagnostics);
        copy.add(diagnostic);
        this.diagnostics = List.copyOf(copy);
    }

    /**
     * B148：是否已经产出过内容块 —— anthropic 中段模型回退块的判据
     * （{@code anthropic-messages.ts:690-693}：fallback 块在已有内容之后才硬错）。
     */
    public boolean hasContentBlocks() {
        return !blocks.isEmpty();
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
        return Math.max(0, textThinking.thinkingBlockIndex);
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
    // Text block（实现见 TextThinkingBlockAccumulator）
    // ═══════════════════════════════════════════════════════════

    /** Emit text-block-start. Adds a placeholder {@link ContentBlock.TextContent}. */
    public StreamEvent.TextStart emitTextStart() {
        return textThinking.emitTextStart();
    }

    /** Emit a text delta. Updates the current text block in-place. */
    public StreamEvent.TextDelta emitTextDelta(String delta) {
        return textThinking.emitTextDelta(delta);
    }

    /**
     * Retain a thought signature onto the current text block
     * （javadoc 逐字随实现搬走，见 {@link TextThinkingBlockAccumulator#retainTextSignature}）。
     */
    public void retainTextSignature(String signature) {
        textThinking.retainTextSignature(signature);
    }

    /**
     * 用**权威内容**替换当前文本块的累计文本
     * （javadoc 逐字随实现搬走，见 {@link TextThinkingBlockAccumulator#replaceText}）。
     */
    public void replaceText(String text) {
        textThinking.replaceText(text);
    }

    /**
     * 就地设停止原因（不经终局事件）
     * （javadoc 逐字随实现搬走，见 {@link TextThinkingBlockAccumulator#forceStopReason}）。
     */
    public void forceStopReason(StopReason reason) {
        textThinking.forceStopReason(reason);
    }

    /**
     * Retain a thought signature onto the current thinking block
     * （javadoc 逐字随实现搬走，见 {@link TextThinkingBlockAccumulator#retainThinkingSignature}）。
     */
    public void retainThinkingSignature(String signature) {
        textThinking.retainThinkingSignature(signature);
    }

    /** Emit text-block-end. The text block is already finalized. */
    public StreamEvent.TextEnd emitTextEnd() {
        return textThinking.emitTextEnd();
    }

    // ═══════════════════════════════════════════════════════════
    // Thinking block（实现见 TextThinkingBlockAccumulator）
    // ═══════════════════════════════════════════════════════════

    /** Emit thinking-block-start. Adds a placeholder {@link ContentBlock.ThinkingContent}. */
    public StreamEvent.ThinkingStart emitThinkingStart() {
        return textThinking.emitThinkingStart();
    }

    /**
     * Emit thinking-block-start carrying the provider's pre-set content
     * （javadoc 逐字随实现搬走，见 {@link TextThinkingBlockAccumulator#emitThinkingStart}）。
     */
    public StreamEvent.ThinkingStart emitThinkingStart(
            String initialText, String initialSignature, boolean redacted) {
        return textThinking.emitThinkingStart(initialText, initialSignature, redacted);
    }

    /** Emit a thinking delta. Updates the current thinking block in-place. */
    public StreamEvent.ThinkingDelta emitThinkingDelta(String delta) {
        return textThinking.emitThinkingDelta(delta);
    }

    /**
     * Append a signature delta to the current thinking block <b>without emitting an event</b>
     * （javadoc 逐字随实现搬走，见 {@link TextThinkingBlockAccumulator#appendThinkingSignature}）。
     */
    public void appendThinkingSignature(String signature) {
        textThinking.appendThinkingSignature(signature);
    }

    /**
     * Write the provider's final signature/redacted flags onto the current
     * thinking block <b>without emitting an event</b>
     * （javadoc 逐字随实现搬走，见 {@link TextThinkingBlockAccumulator#applyThinkingSignature}）。
     */
    public void applyThinkingSignature(String signature, boolean redacted) {
        textThinking.applyThinkingSignature(signature, redacted);
    }

    /**
     * Stamp the finalized Responses reasoning item: authoritative visible
     * text plus the whole item serialized as the block signature
     * （javadoc 逐字随实现搬走，见 {@link TextThinkingBlockAccumulator#stampReasoning}）。
     */
    public void stampReasoning(String text, String itemJson) {
        textThinking.stampReasoning(text, itemJson);
    }

    /**
     * Restamp the signature on the thinking block at the given index
     * （javadoc 逐字随实现搬走，见 {@link TextThinkingBlockAccumulator#restampBlockSignature}）。
     */
    public void restampBlockSignature(int blockIndex, String signature) {
        textThinking.restampBlockSignature(blockIndex, signature);
    }

    /** Emit thinking-block-end. */
    public StreamEvent.ThinkingEnd emitThinkingEnd() {
        return textThinking.emitThinkingEnd();
    }

    // ═══════════════════════════════════════════════════════════
    // Tool call block（实现见 ToolCallBlockAccumulator）
    // ═══════════════════════════════════════════════════════════

    /** Emit tool-call-start carrying the call's identity（javadoc 逐字随迁，见卫星类）。 */
    public StreamEvent.ToolCallStart emitToolCallStart(String id, String name) {
        return toolCalls.emitToolCallStart(id, name);
    }

    /** 批 30（ai#8）：namespace 形起点（javadoc 见卫星类）。 */
    public StreamEvent.ToolCallStart emitToolCallStart(String id, String name, String namespace) {
        return toolCalls.emitToolCallStart(id, name, namespace);
    }

    /**
     * 用**收尾项里的权威参数**替换流式累积的草稿
     * （javadoc 逐字随迁，见 {@link ToolCallBlockAccumulator#replaceToolArguments}）。
     */
    public void replaceToolArguments(String argumentsJson) {
        toolCalls.replaceToolArguments(argumentsJson);
    }

    /** Emit a tool-call argument delta. */
    public StreamEvent.ToolCallDelta emitToolCallDelta(String id, String jsonDelta) {
        return toolCalls.emitToolCallDelta(id, jsonDelta);
    }

    /** Emit tool-call-end with the full tool name, ID, and parsed arguments. */
    public StreamEvent.ToolCallEnd emitToolCallEnd(String id, String name) {
        return toolCalls.emitToolCallEnd(id, name);
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
        return toolCalls.emitToolCallEnd(id, name, thoughtSignature);
    }

    /** 批 30（ai#8）：namespace 形收尾（javadoc 见卫星类）。 */
    public StreamEvent.ToolCallEnd emitToolCallEnd(
            String id, String name, String thoughtSignature, String namespace) {
        return toolCalls.emitToolCallEnd(id, name, thoughtSignature, namespace);
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
     * <p>包 H1 步 2（{@code 原 docs/42 §8.2}）：这是加宽后的入口。此前 {@code emitUsage}
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
    public StreamEvent.StreamDone emitDone(StopReason reason) {
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
     * ⇒ 错误文本只能由下游各自从 {@code Throwable} 反推（{@code 原 docs/55 §5 F1/F2}）。</p>
     *
     * <p>内容（{@link #snapshot()} 的 blocks）本来就保留 —— 那是 pi 的实测行为
     * （{@code 原 docs/55 §3.1} P4：中止时 {@code error.content} 是流到中止点的文本）。</p>
     */
    public StreamEvent.StreamError emitError(StopReason reason, Throwable error) {
        var settled = StreamEvent.StreamError.settle(reason, error, snapshot());
        // 让 builder 自身的状态与事件一致（快照上已落定，字段不能还停在旧值）。
        this.stopReason = settled.partial().stopReason();
        return settled;
    }

}
