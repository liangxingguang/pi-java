package com.pijava.ai.stream;

import java.util.Map;

import com.pijava.ai.message.ContentBlock;

/**
 * Tool-call block accumulator of {@link StreamPartialBuilder}
 * （批 30 结构拆分——纯移动零行为变化，{@link TextThinkingBlockAccumulator} 先例）。
 * 自持工具调用的参数缓冲/身份/namespace 与块下标，经包级访问操作主类的
 * {@code blocks}/{@code nextContentIndex}。
 */
final class ToolCallBlockAccumulator {

    private final StreamPartialBuilder owner;

    // Per-block accumulators
    private final StringBuilder toolArgBuf = new StringBuilder();
    private String toolCallId = "";
    private String toolCallName = "";
    /**
     * 批 30（ai#8）：当前工具调用的 Responses namespace（pi
     * {@code StreamingToolCall.namespace}——起点建块即写，收尾项带值时覆盖，
     * openai-responses-shared.ts:492/:513/:717/:733 对位）。
     */
    private String toolCallNamespace;

    // Each stream owns its block index so interleaved text/thinking/tool
    // deltas never overwrite each other's block (they used to target
    // blocks.size()-1, which corrupted the snapshot when streams alternated).
    private int toolBlockIndex = -1;

    ToolCallBlockAccumulator(StreamPartialBuilder owner) {
        this.owner = owner;
    }

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
    StreamEvent.ToolCallStart emitToolCallStart(String id, String name) {
        return emitToolCallStart(id, name, null);
    }

    /**
     * 批 30（ai#8）：namespace 形起点（pi shared :485-527——起点 item 带
     * {@code namespace} 时直接写进块）。
     *
     * @param namespace Responses namespace；null ≙ 缺席
     */
    StreamEvent.ToolCallStart emitToolCallStart(String id, String name, String namespace) {
        toolArgBuf.setLength(0);
        toolCallId = id == null ? "" : id;
        toolCallName = name == null ? "" : name;
        toolCallNamespace = namespace;
        toolBlockIndex = owner.blocks.size();
        owner.blocks.add(new ContentBlock.ToolUseContent(toolCallId, toolCallName, Map.of(), null,
            toolCallNamespace));
        int idx = owner.nextContentIndex++;
        return new StreamEvent.ToolCallStart(idx, owner.snapshot());
    }

    /**
     * pi {@code openai-responses-shared.ts:710} 的
     * {@code item.arguments || partialJson || "{}"}：用**收尾项里的权威参数**替换流式累积的
     * 草稿 —— delta 可能比它短，也可能整个没有（兼容端点只在 {@code output_item.done} 给）。
     *
     * <p>只改缓冲：紧随其后的 {@link #emitToolCallEnd} 会用新缓冲解析并落块。</p>
     */
    void replaceToolArguments(String argumentsJson) {
        toolArgBuf.setLength(0);
        toolArgBuf.append(argumentsJson);
    }

    /** Emit a tool-call argument delta. */
    StreamEvent.ToolCallDelta emitToolCallDelta(String id, String jsonDelta) {
        this.toolCallId = id;
        toolArgBuf.append(jsonDelta);
        if (toolBlockIndex < 0) {
            toolBlockIndex = owner.blocks.size();
            owner.blocks.add(new ContentBlock.ToolUseContent("", "", Map.of(), null,
                toolCallNamespace));
            owner.nextContentIndex++;
        }
        int idx = toolBlockIndex;
        owner.blocks.set(idx, new ContentBlock.ToolUseContent(
            toolCallId, toolCallName, ToolArgumentParser.parse(toolArgBuf.toString()), null,
            toolCallNamespace));
        return new StreamEvent.ToolCallDelta(idx, id, jsonDelta, owner.snapshot());
    }

    /** Emit tool-call-end with the full tool name, ID, and parsed arguments. */
    StreamEvent.ToolCallEnd emitToolCallEnd(String id, String name) {
        return emitToolCallEnd(id, name, null, null);
    }

    /**
     * Emit tool-call-end carrying the part's thought signature
     * (pi {@code ...(part.thoughtSignature && { thoughtSignature })}，
     * {@code google-generative-ai.ts:201-207}).
     *
     * @param thoughtSignature base64 signature attached to the functionCall
     *        part, or null when absent
     */
    StreamEvent.ToolCallEnd emitToolCallEnd(
            String id, String name, String thoughtSignature) {
        return emitToolCallEnd(id, name, thoughtSignature, null);
    }

    /**
     * 批 30（ai#8）：namespace 形收尾（pi shared :717/:733——收尾 item 带
     * {@code namespace} 时覆盖起点值，缺席保留；pi
     * {@code if (item.namespace !== undefined) slot.block.namespace = item.namespace}）。
     *
     * @param namespace 收尾项的 namespace；null ≙ 缺席（保留起点值）
     */
    StreamEvent.ToolCallEnd emitToolCallEnd(
            String id, String name, String thoughtSignature, String namespace) {
        this.toolCallId = id;
        this.toolCallName = name;
        if (namespace != null) {
            this.toolCallNamespace = namespace;
        }
        int idx = Math.max(0, toolBlockIndex);
        Map<String, Object> args = ToolArgumentParser.parse(toolArgBuf.toString());
        owner.blocks.set(idx, new ContentBlock.ToolUseContent(id, name, args, thoughtSignature,
            toolCallNamespace));
        return new StreamEvent.ToolCallEnd(idx, id, name, args, toolCallNamespace,
            owner.snapshot());
    }
}
