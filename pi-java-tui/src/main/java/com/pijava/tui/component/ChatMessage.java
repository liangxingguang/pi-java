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
    /** Tool result content blocks (text and/or diff), for rich rendering (P6-26). */
    record ToolResult(List<ContentBlock> content, boolean isError) implements ChatMessage {
        /** Defensively copies the content blocks. */
        public ToolResult {
            content = List.copyOf(content);
        }
    }
    record Error(String message) implements ChatMessage {}
    /** Metadata/system bubble; {@link MetaKind} drives icon + color (P6-25). */
    record System(String text, MetaKind kind) implements ChatMessage {
        /** Defaults a null kind to {@link MetaKind#GENERIC}. */
        public System {
            kind = kind == null ? MetaKind.GENERIC : kind;
        }
    }
    /** Inter-turn divider with an optional runtime label (Codex CLI style). */
    record TurnSeparator(String label) implements ChatMessage {}

    /**
     * Project an agent-core {@link Entry} into a chat bubble. Returns
     * {@code null} for entries that must not be rendered (a custom message
     * with {@code display=false}, aligned with pi's interactive-mode gate).
     */
    static ChatMessage from(Entry entry) {
        return switch (entry) {
            case Entry.Message message -> fromMessage(message);
            case Entry.ModelChange change ->
                new System("Model: " + change.provider() + "/" + change.modelId(),
                    MetaKind.MODEL_CHANGE);
            case Entry.ThinkingLevelChange change ->
                new System("Thinking: " + change.thinkingLevel(),
                    MetaKind.THINKING_LEVEL);
            case Entry.Compaction compaction ->
                new System("Compacted context: " + compaction.summary(),
                    MetaKind.COMPACTION);

            case Entry.BranchSummary summary ->
                new System("Branch: " + summary.summary(), MetaKind.BRANCH);
            case Entry.Custom custom ->
                new System("Custom event: " + custom.customType(), MetaKind.CUSTOM);
            case Entry.CustomMessage customMessage -> customMessage.display()
                ? new System("[" + customMessage.customType() + "] "
                    + customMessage.content().plainText(), MetaKind.CUSTOM)
                : null;
            case Entry.Usage usage -> cacheWarmNotice(usage);
        };
    }

    /**
     * usage 条目的渲染判据**只有 {@code kind:"cache_warm"}**。
     *
     * <p>pi 的交互模式正是这么做的：{@code interactive-mode.ts:3401} 与 {@code :4030} 都写成
     * {@code entry.type === "usage" && entry.kind === "cache_warm"}，而
     * {@code tree-selector.ts:341} 对 usage 条目**一律**返回 false。本仓自己产的 kind 是
     * {@code "assistant"}/{@code "hook"} ⇒ 在这里返回 {@code null}，与 pi 对这两类的行为一致
     * （每轮渲染一个用量气泡会比 pi 吵得多）；从 pi 文件读进来的 {@code cache_warm} 通知照渲染。</p>
     */
    private static ChatMessage cacheWarmNotice(Entry.Usage usage) {
        if (!"cache_warm".equals(usage.kind()) || usage.usage() == null) {
            return null;
        }
        return new System("Cache warming: " + (long) usage.usage().totalTokens() + " tokens",
            MetaKind.GENERIC);
    }

    private static ChatMessage fromMessage(Entry.Message message) {
        var content = message.message().content();
        return switch (message.message().role()) {
            case "user" -> new User(joinText(content));
            case "assistant" -> new Assistant(content);
            case "tool" -> fromToolBlocks(content);
            default -> new System("Unknown message role: " + message.message().role(),
                MetaKind.GENERIC);
        };
    }

    private static ChatMessage fromToolBlocks(List<ContentBlock> blocks) {
        if (blocks.isEmpty()) {
            return new ToolResult(List.of(), false);
        }
        var block = blocks.get(0);
        if (block instanceof ContentBlock.ToolResultContent result) {
            return new ToolResult(result.content(), result.isError());
        }
        return new ToolResult(blocks, false);
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
