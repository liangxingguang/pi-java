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
    private String stopReason;

    // Per-block accumulators
    private final StringBuilder textBuf = new StringBuilder();
    private final StringBuilder thinkingBuf = new StringBuilder();
    private final StringBuilder toolArgBuf = new StringBuilder();
    private String toolCallId = "";
    private String toolCallName = "";

    private int nextContentIndex;

    public StreamPartialBuilder(String messageId) {
        this.messageId = messageId;
    }

    public StreamPartialBuilder() {
        this(java.util.UUID.randomUUID().toString());
    }

    // ── Snapshot ─────────────────────────────────────────────

    /** Return the current {@link AssistantMessage} snapshot. */
    public AssistantMessage snapshot() {
        return new AssistantMessage(messageId, List.copyOf(blocks), usage, stopReason);
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
        blocks.add(new ContentBlock.TextContent(""));
        int idx = nextContentIndex++;
        return new StreamEvent.TextStart(idx, snapshot());
    }

    /** Emit a text delta. Updates the current text block in-place. */
    public StreamEvent.TextDelta emitTextDelta(String delta) {
        textBuf.append(delta);
        // Replace the last block with accumulated text
        int idx = blocks.size() - 1;
        blocks.set(idx, new ContentBlock.TextContent(textBuf.toString()));
        return new StreamEvent.TextDelta(idx, delta, snapshot());
    }

    /** Emit text-block-end. The text block is already finalized. */
    public StreamEvent.TextEnd emitTextEnd() {
        int idx = blocks.size() - 1;
        return new StreamEvent.TextEnd(idx, textBuf.toString(), snapshot());
    }

    // ═══════════════════════════════════════════════════════════
    // Thinking block
    // ═══════════════════════════════════════════════════════════

    /** Emit thinking-block-start. Adds a placeholder {@link ContentBlock.TextContent}. */
    public StreamEvent.ThinkingStart emitThinkingStart() {
        thinkingBuf.setLength(0);
        blocks.add(new ContentBlock.TextContent(""));
        int idx = nextContentIndex++;
        return new StreamEvent.ThinkingStart(idx, snapshot());
    }

    /** Emit a thinking delta. Updates the current thinking block in-place. */
    public StreamEvent.ThinkingDelta emitThinkingDelta(String delta) {
        thinkingBuf.append(delta);
        int idx = blocks.size() - 1;
        blocks.set(idx, new ContentBlock.TextContent(thinkingBuf.toString()));
        return new StreamEvent.ThinkingDelta(idx, delta, snapshot());
    }

    /** Emit thinking-block-end. */
    public StreamEvent.ThinkingEnd emitThinkingEnd() {
        int idx = blocks.size() - 1;
        return new StreamEvent.ThinkingEnd(idx, thinkingBuf.toString(), snapshot());
    }

    // ═══════════════════════════════════════════════════════════
    // Tool call block
    // ═══════════════════════════════════════════════════════════

    /** Emit tool-call-start. Adds a placeholder {@link ContentBlock.ToolUseContent}. */
    public StreamEvent.ToolCallStart emitToolCallStart() {
        toolArgBuf.setLength(0);
        toolCallId = "";
        toolCallName = "";
        blocks.add(new ContentBlock.ToolUseContent("", "", Map.of()));
        int idx = nextContentIndex++;
        return new StreamEvent.ToolCallStart(idx, snapshot());
    }

    /** Emit a tool-call argument delta. */
    public StreamEvent.ToolCallDelta emitToolCallDelta(String id, String jsonDelta) {
        this.toolCallId = id;
        toolArgBuf.append(jsonDelta);
        int idx = blocks.size() - 1;
        parseAndSetToolBlock(idx);
        return new StreamEvent.ToolCallDelta(idx, id, jsonDelta, snapshot());
    }

    /** Emit tool-call-end with the full tool name, ID, and parsed arguments. */
    public StreamEvent.ToolCallEnd emitToolCallEnd(String id, String name) {
        this.toolCallId = id;
        this.toolCallName = name;
        int idx = blocks.size() - 1;
        Map<String, Object> args = parseArgs();
        blocks.set(idx, new ContentBlock.ToolUseContent(id, name, args));
        return new StreamEvent.ToolCallEnd(idx, id, name, args, snapshot());
    }

    // ═══════════════════════════════════════════════════════════
    // Meta events
    // ═══════════════════════════════════════════════════════════

    /** Emit usage info. */
    public StreamEvent.UsageInfo emitUsage(long inputTokens, long outputTokens) {
        this.usage = new StreamEvent.UsageInfo(inputTokens, outputTokens, snapshot());
        return this.usage;
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
            return (Map<String, Object>) (Map<?, ?>)
                    new com.fasterxml.jackson.databind.ObjectMapper()
                            .readValue(toolArgBuf.toString(), Map.class);
        } catch (Exception e) {
            return Map.of("_raw", toolArgBuf.toString());
        }
    }

    private void parseAndSetToolBlock(int idx) {
        Map<String, Object> args = parseArgs();
        blocks.set(idx, new ContentBlock.ToolUseContent(toolCallId, toolCallName, args));
    }
}
