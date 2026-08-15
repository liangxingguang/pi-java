package com.pijava.tui.component;

import java.util.List;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.message.ContentBlock;

/**
 * TUI-internal message model: projects agent-core {@link Entry} values into
 * flat, render-friendly bubbles (Phase 3 design §4.1).
 */
public sealed interface ChatMessage {

    record User(String text) implements ChatMessage {}
    record Assistant(List<ContentBlock> blocks) implements ChatMessage {}
    record ToolCall(String name, String arguments) implements ChatMessage {}
    record ToolResult(String output, boolean isError) implements ChatMessage {}
    record Error(String message) implements ChatMessage {}
    record System(String text) implements ChatMessage {}
    /** Inter-turn divider with an optional runtime label (Codex CLI style). */
    record TurnSeparator(String label) implements ChatMessage {}

    /** Project an agent-core {@link Entry} into a chat bubble. */
    static ChatMessage from(Entry entry) {
        return switch (entry) {
            case Entry.Message message -> fromMessage(message);
            case Entry.ModelChange change ->
                new System("Model: " + change.provider() + "/" + change.modelId());
            case Entry.ThinkingLevelChange change ->
                new System("Thinking: " + change.level());
            case Entry.ActiveToolsChange change ->
                new System("Tools: " + String.join(", ", change.toolNames()));
            case Entry.Compaction compaction ->
                new System("Compacted context (" + compaction.entriesBefore()
                    + " → " + compaction.entriesAfter() + ")");
            case Entry.BranchSummary summary ->
                new System("Branch: " + summary.summary());
            case Entry.Custom custom ->
                new System("Custom event: " + custom.kind());
        };
    }

    private static ChatMessage fromMessage(Entry.Message message) {
        return switch (message.role()) {
            case "user" -> new User(joinText(message.blocks()));
            case "assistant" -> new Assistant(message.blocks());
            case "tool" -> fromToolBlocks(message.blocks());
            default -> new System("Unknown message role: " + message.role());
        };
    }

    private static ChatMessage fromToolBlocks(List<ContentBlock> blocks) {
        if (blocks.isEmpty()) {
            return new ToolResult("", false);
        }
        var block = blocks.get(0);
        if (block instanceof ContentBlock.ToolResultContent result) {
            return new ToolResult(joinText(result.content()), result.isError());
        }
        return new ToolResult(joinText(blocks), false);
    }

    private static String joinText(List<ContentBlock> blocks) {
        var builder = new StringBuilder();
        for (var block : blocks) {
            if (block instanceof ContentBlock.TextContent text) {
                builder.append(text.text());
            }
        }
        return builder.toString();
    }
}
