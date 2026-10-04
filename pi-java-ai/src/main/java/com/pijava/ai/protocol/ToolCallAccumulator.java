package com.pijava.ai.protocol;

import java.util.Map;
import java.util.function.Consumer;

import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.stream.StreamPartialBuilder;

/**
 * Accumulates one streaming tool call across arbitrarily split chunks.
 *
 * <p>OpenAI-compatible endpoints (DeepSeek in particular) do not guarantee
 * that {@code id}, {@code function.name} and {@code function.arguments}
 * arrive in the same chunk — the first chunk may carry only the id, the next
 * only name + arguments. Accumulating by slot and emitting
 * {@code ToolCallStart} on the FIRST tool-call chunk (whatever it contains)
 * ensures the call is never dropped. Before this, a split arrival left the
 * tool call unstarted: the run then "completed" with only the preamble text
 * and the tool was never executed.</p>
 *
 * <p>原 docs/69：custom（grammar）chunks 走 {@link GrammarInputBuffer}：首帧
 * custom-only（无 function）时建缓冲，属性取 grammar 表、未知名回落 {@code "input"}，
 * 收尾先关闭再 end。</p>
 *
 * <p>Matches {@link StreamPartialBuilder}'s single-tool-call model; parallel
 * tool calls would need multi-slot support in the builder.</p>
 */
final class ToolCallAccumulator {

    private boolean started;
    private String id = "";
    private String name = "";
    private GrammarInputBuffer customBuffer;

    /** Apply one chunk's optional function fields and forward any emitted events. */
    void update(String chunkId, String chunkName, String chunkArguments,
                Consumer<StreamEvent> emit, StreamPartialBuilder builder) {
        if (chunkId != null) {
            id = chunkId;
        }
        if (chunkName != null) {
            name = chunkName;
        }
        if (!started) {
            started = true;
            // 包⑥：起点即带身份 —— 此刻**可能只知其一**（首块只给 id），
            // 另一处为空串，与 pi 的 completions 车道同形（P6）。
            emit.accept(builder.emitToolCallStart(id, name));
        }
        if (chunkArguments != null) {
            emit.accept(builder.emitToolCallDelta(id, chunkArguments));
        }
    }

    /** Apply one custom-tool chunk (pi ensureToolCallBlock + custom input logic). */
    void updateCustom(String chunkId, CompletionsCustomChunks.CustomChunk custom,
                       Map<String, String> grammarProperties,
                       Consumer<StreamEvent> emit, StreamPartialBuilder builder) {
        var chunkName = custom.name();
        if (!started) {
            // pi :504-505：custom-only 首帧，属性取 grammar 表，未知名/无名回落 "input"。
            var property = grammarProperties.get(chunkName == null ? "" : chunkName);
            customBuffer = new GrammarInputBuffer(property != null ? property : "input");
            started = true;
            emit.accept(builder.emitToolCallStart(
                chunkId == null ? "" : chunkId,
                chunkName == null ? "" : chunkName));
        }
        if (chunkId != null) {
            id = chunkId;
        }
        if (chunkName != null) {
            name = chunkName;
        }
        if (custom.input() != null) {
            var nextInput = customBuffer.input() + custom.input();
            var fragment = customBuffer.append(nextInput, false);
            // pi :653：片段为 undefined 时照发空串 delta（同一帧形状）。
            emit.accept(builder.emitToolCallDelta(id, fragment == null ? "" : fragment));
        }
    }

    /** Emit closing delta (custom) and {@code ToolCallEnd} if any tool-call chunk was seen. */
    void finish(Consumer<StreamEvent> emit, StreamPartialBuilder builder) {
        if (!started) {
            return;
        }
        if (customBuffer != null && !customBuffer.closed()) {
            // pi finishBlock:448-457 —— 关闭 custom 输入，有片段则补发 delta。
            var fragment = customBuffer.append(customBuffer.input(), true);
            if (fragment != null) {
                emit.accept(builder.emitToolCallDelta(id, fragment));
            }
        }
        emit.accept(builder.emitToolCallEnd(id, name));
    }

    /** Whether any tool-call chunk was seen (drives the stop reason). */
    boolean started() {
        return started;
    }
}
