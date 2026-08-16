package com.pijava.ai.message;

import java.util.List;
import java.util.UUID;

import com.pijava.ai.stream.StreamEvent;

/**
 * A streaming snapshot of an assistant message being built.
 *
 * <p>Unlike {@link Message.AssistantMessage} (which is the final static
 * LLM response), this record is updated continuously as each
 * {@link StreamEvent} arrives. Every {@code StreamEvent} carries its
 * current {@code partial} snapshot, enabling consumers (notably
 * {@code AgentHarness}) to simply replace the last message in context
 * rather than accumulating deltas manually.</p>
 *
 * <p>This type lives in {@code pi-java-ai} (not {@code pi-java-agent-core})
 * so that protocol adapters can produce it without a reverse dependency.</p>
 *
 * @param id        unique message identifier (generated on first event)
 * @param content   current content blocks (text, thinking, tool calls)
 * @param usage     token usage so far (may be null early in the stream)
 * @param stopReason final stop reason ("end_turn", "stop", "tool_use",
 *                   "error", "length", "aborted"), or null if still streaming
 */
public record AssistantMessage(
    String id,
    List<ContentBlock> content,
    StreamEvent.UsageInfo usage,
    String stopReason
) {
    /** Compact constructor that defensively copies the content blocks. */
    public AssistantMessage {
        content = List.copyOf(content);
    }

    /** Create an empty initial snapshot. */
    public static AssistantMessage empty() {
        return new AssistantMessage(
            UUID.randomUUID().toString(),
            List.of(),
            null,
            null
        );
    }

    /** Create a copy with a new id. */
    public AssistantMessage withId(String newId) {
        return new AssistantMessage(newId, content, usage, stopReason);
    }

    /** Create a copy with updated content blocks. */
    public AssistantMessage withContent(List<ContentBlock> newContent) {
        return new AssistantMessage(id, newContent, usage, stopReason);
    }

    /** Create a copy with updated usage. */
    public AssistantMessage withUsage(StreamEvent.UsageInfo newUsage) {
        return new AssistantMessage(id, content, newUsage, stopReason);
    }

    /** Create a copy with an updated stop reason. */
    public AssistantMessage withStopReason(String newStopReason) {
        return new AssistantMessage(id, content, usage, newStopReason);
    }
}
