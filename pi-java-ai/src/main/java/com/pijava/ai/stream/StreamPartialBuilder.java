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
    // Each stream owns its block index so interleaved text/thinking/tool
    // deltas never overwrite each other's block (they used to target
    // blocks.size()-1, which corrupted the snapshot when streams alternated).
    private int textBlockIndex = -1;
    private int thinkingBlockIndex = -1;
    private int toolBlockIndex = -1;

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
        thinkingBuf.setLength(0);
        thinkingBlockIndex = blocks.size();
        blocks.add(new ContentBlock.ThinkingContent(""));
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
        blocks.set(idx, new ContentBlock.ThinkingContent(thinkingBuf.toString()));
        return new StreamEvent.ThinkingDelta(idx, delta, snapshot());
    }

    /** Emit thinking-block-end. */
    public StreamEvent.ThinkingEnd emitThinkingEnd() {
        int idx = Math.max(0, thinkingBlockIndex);
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
        toolBlockIndex = blocks.size();
        blocks.add(new ContentBlock.ToolUseContent("", "", Map.of()));
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
