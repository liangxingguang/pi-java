package com.pijava.ai.stream;

import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.StopReason;

/**
 * Text/thinking block accumulators of {@link StreamPartialBuilder}
 * （结构债拆分——纯移动零行为变化）。自持 text/thinking 的缓冲与块下标，
 * 经包级访问操作主类的 {@code blocks}/{@code nextContentIndex}/{@code stopReason}。
 */
final class TextThinkingBlockAccumulator {

    private final StreamPartialBuilder owner;

    // Per-block accumulators
    private final StringBuilder textBuf = new StringBuilder();
    // Signature riding on the current text block (Google text parts). Empty
    // buffer ⇒ null on the block (pi never persists an empty signature key).
    private final StringBuilder textSigBuf = new StringBuilder();
    private final StringBuilder thinkingBuf = new StringBuilder();
    private final StringBuilder thinkingSigBuf = new StringBuilder();
    // Redacted flag of the *current* thinking block. Carried on every block
    // rewrite so a later signature/delta cannot silently clear it.
    private boolean thinkingRedacted;

    // Each stream owns its block index so interleaved text/thinking/tool
    // deltas never overwrite each other's block (they used to target
    // blocks.size()-1, which corrupted the snapshot when streams alternated).
    private int textBlockIndex = -1;
    int thinkingBlockIndex = -1;

    TextThinkingBlockAccumulator(StreamPartialBuilder owner) {
        this.owner = owner;
    }

    /** Emit text-block-start. Adds a placeholder {@link ContentBlock.TextContent}. */
    StreamEvent.TextStart emitTextStart() {
        textBuf.setLength(0);
        textSigBuf.setLength(0);
        textBlockIndex = owner.blocks.size();
        owner.blocks.add(new ContentBlock.TextContent(""));
        int idx = owner.nextContentIndex++;
        return new StreamEvent.TextStart(idx, owner.snapshot());
    }

    /** Emit a text delta. Updates the current text block in-place. */
    StreamEvent.TextDelta emitTextDelta(String delta) {
        textBuf.append(delta);
        if (textBlockIndex < 0) {
            // A text delta without a preceding start: create the block lazily.
            textBlockIndex = owner.blocks.size();
            owner.blocks.add(new ContentBlock.TextContent(""));
            owner.nextContentIndex++;
        }
        int idx = textBlockIndex;
        owner.blocks.set(idx, new ContentBlock.TextContent(
            textBuf.toString(), textSignatureOrNull()));
        return new StreamEvent.TextDelta(idx, delta, owner.snapshot());
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
    void retainTextSignature(String signature) {
        if (signature != null && !signature.isEmpty()) {
            textSigBuf.setLength(0);
            textSigBuf.append(signature);
            if (textBlockIndex >= 0) {
                owner.blocks.set(textBlockIndex, new ContentBlock.TextContent(
                    textBuf.toString(), textSigBuf.toString()));
            }
        }
    }

    /**
     * pi {@code openai-responses-shared.ts:700} 的 {@code slot.block.text = …}：用
     * **权威内容**替换当前文本块的累计文本（流式 delta 只是草稿）。块下标未定
     * （还没有 text 块）时只改缓冲。
     *
     * <p>⚠️ 调用方负责「空内容不覆盖」的判定（原 docs/71 R4 的刻意偏差）。</p>
     */
    void replaceText(String text) {
        textBuf.setLength(0);
        textBuf.append(text);
        if (textBlockIndex >= 0) {
            owner.blocks.set(textBlockIndex, new ContentBlock.TextContent(text, textSignatureOrNull()));
        }
    }

    /**
     * pi {@code openai-responses-shared.ts:442-446} 的 {@code output.stopReason = "stop"}：
     * 就地设停止原因（不经终局事件）。⚠️ 终局事件的映射会再覆盖一次 —— 两侧同形
     * （pi {@code :590}），所以这一支只在 {@code text_end} 的 {@code partial} 上可观察。
     */
    /** 枚举权威版（批 1 ⑥ B119/B121）。 */
    void forceStopReason(StopReason reason) {
        owner.stopReason = reason;
    }

    /**
     * Retain a thought signature onto the current thinking block during
     * streaming (same pi function, {@code google-generative-ai.ts:149-152}).
     * Non-empty incoming overwrites; empty/null does not erase.
     */
    void retainThinkingSignature(String signature) {
        if (signature != null && !signature.isEmpty()) {
            thinkingSigBuf.setLength(0);
            thinkingSigBuf.append(signature);
            if (thinkingBlockIndex >= 0) {
                owner.blocks.set(thinkingBlockIndex, new ContentBlock.ThinkingContent(
                    thinkingBuf.toString(), thinkingSigBuf.toString(), thinkingRedacted));
            }
        }
    }

    /** Emit text-block-end. The text block is already finalized. */
    StreamEvent.TextEnd emitTextEnd() {
        int idx = Math.max(0, textBlockIndex);
        return new StreamEvent.TextEnd(idx, textBuf.toString(), owner.snapshot());
    }

    /** Emit thinking-block-start. Adds a placeholder {@link ContentBlock.ThinkingContent}. */
    StreamEvent.ThinkingStart emitThinkingStart() {
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
    StreamEvent.ThinkingStart emitThinkingStart(
            String initialText, String initialSignature, boolean redacted) {
        var text = initialText == null ? "" : initialText;
        var sig = initialSignature == null ? "" : initialSignature;
        thinkingBuf.setLength(0);
        thinkingBuf.append(text);
        thinkingSigBuf.setLength(0);
        thinkingSigBuf.append(sig);
        thinkingRedacted = redacted;
        thinkingBlockIndex = owner.blocks.size();
        owner.blocks.add(new ContentBlock.ThinkingContent(text, sig, redacted));
        int idx = owner.nextContentIndex++;
        return new StreamEvent.ThinkingStart(idx, owner.snapshot());
    }

    /** Emit a thinking delta. Updates the current thinking block in-place. */
    StreamEvent.ThinkingDelta emitThinkingDelta(String delta) {
        thinkingBuf.append(delta);
        if (thinkingBlockIndex < 0) {
            thinkingBlockIndex = owner.blocks.size();
            owner.blocks.add(new ContentBlock.ThinkingContent(""));
            owner.nextContentIndex++;
        }
        int idx = thinkingBlockIndex;
        owner.blocks.set(idx, new ContentBlock.ThinkingContent(
            thinkingBuf.toString(), thinkingSigBuf.toString(), thinkingRedacted));
        return new StreamEvent.ThinkingDelta(idx, delta, owner.snapshot());
    }

    /**
     * Append a signature delta to the current thinking block <b>without emitting an event</b>
     * (Anthropic {@code signature_delta}).
     *
     * <p>对位 {@code anthropic-messages.ts:773-779}：pi 只做
     * {@code block.thinkingSignature = (block.thinkingSignature || "") + signature}，
     * <b>不 push 任何流事件</b>——签名随下一个增量帧的 partial 或 thinking_end 可见。
     * 这是 append 语义（多段 signature_delta 累积），不要与替换语义的
     * {@link #applyThinkingSignature}（pi-messages 线）混用。块不存在时 no-op，对位
     * pi 的 {@code findIndex} 落空。</p>
     */
    void appendThinkingSignature(String signature) {
        thinkingSigBuf.append(signature == null ? "" : signature);
        if (thinkingBlockIndex >= 0) {
            owner.blocks.set(thinkingBlockIndex, new ContentBlock.ThinkingContent(
                thinkingBuf.toString(), thinkingSigBuf.toString(), thinkingRedacted));
        }
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
    void applyThinkingSignature(String signature, boolean redacted) {
        thinkingSigBuf.setLength(0);
        thinkingSigBuf.append(signature == null ? "" : signature);
        thinkingRedacted = redacted;
        if (thinkingBlockIndex >= 0) {
            owner.blocks.set(thinkingBlockIndex, new ContentBlock.ThinkingContent(
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
    void stampReasoning(String text, String itemJson) {
        if (text != null) {
            thinkingBuf.setLength(0);
            thinkingBuf.append(text);
        }
        thinkingSigBuf.setLength(0);
        thinkingSigBuf.append(itemJson == null ? "" : itemJson);
        thinkingRedacted = false;
        if (thinkingBlockIndex >= 0) {
            owner.blocks.set(thinkingBlockIndex, new ContentBlock.ThinkingContent(
                thinkingBuf.toString(), thinkingSigBuf.toString(), false));
        }
    }

    /**
     * Restamp the signature on the thinking block at the given index,
     * without touching the text buffers (Azure backfill, pi {@code :533-549}).
     */
    void restampBlockSignature(int blockIndex, String signature) {
        if (blockIndex < 0 || blockIndex >= owner.blocks.size()) {
            return;
        }
        if (owner.blocks.get(blockIndex) instanceof ContentBlock.ThinkingContent th) {
            owner.blocks.set(blockIndex, new ContentBlock.ThinkingContent(
                th.text(), signature, th.redacted()));
        }
    }

    /** Emit thinking-block-end. */
    StreamEvent.ThinkingEnd emitThinkingEnd() {
        int idx = Math.max(0, thinkingBlockIndex);
        return new StreamEvent.ThinkingEnd(idx, thinkingBuf.toString(), owner.snapshot());
    }
}
