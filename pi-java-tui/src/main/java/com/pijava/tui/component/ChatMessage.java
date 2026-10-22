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
     * 批 12 T6（docs/23 §3）：{@code outputPad} 烘焙包装——append 时记下当时的
     * 水平留白（0|1），改值<b>不回溯</b>已渲染条目（窄差 D62：pi 遍历容器
     * duck-type 热更；java 新条目用新值）。渲染语义：每行左 indent 加 pad、
     * 内容宽减 2×pad（{@link com.pijava.tui.component.ChatViewportElement}）。
     */
    record Padded(ChatMessage inner, int pad) implements ChatMessage {
        /** Canonical：pad 下限 0。 */
        public Padded {
            pad = Math.max(0, pad);
        }
    }

    /** 消息的烘焙 pad（非 {@link Padded} ⇒ 0）。 */
    static int padOf(ChatMessage message) {
        return message instanceof Padded padded ? padded.pad() : 0;
    }

    /**
     * Project an agent-core {@link Entry} into a chat bubble. Returns
     * {@code null} for entries that must not be rendered (a custom message
     * with {@code display=false}, aligned with pi's interactive-mode gate).
     */
    static ChatMessage from(Entry entry) {
        // 旧签名保测试面（零 pad）；生产默认 1 由 ChatScreen 经
        // uiPrefs().getOutputPad() 注入（pi 默认 outputPad=1 在 accessor 回落）。
        return from(entry, 0);
    }

    /**
     * 批 12 T6：带 {@code outputPad} 的投影——pad ≤ 0 不包装（与 {@link #from(Entry)}
     * 逐字节同形），pad 1 包 {@link Padded}（append 时烘焙，D62 不回溯）。
     */
    static ChatMessage from(Entry entry, int pad) {
        var projected = project(entry);
        return projected == null || pad <= 0 ? projected : new Padded(projected, pad);
    }

    private static ChatMessage project(Entry entry) {
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
            // #57（docs/10 §3.3 ⑧）：bashExecution 按消息条目同格渲染，文本取 pi
            // bashExecutionToText（!! 条目持久化但不进上下文，仍照 pi 显示；
            // 渲染格是 R3 域窄差，不逐字）。
            case Entry.BashExecution bash ->
                new User(com.pijava.agent.session.ContextEntries.bashExecutionText(bash));
            // 默认视图隐藏（pi tree-selector.ts:360-362 把 context_edit 列为 settings entry）。
            case Entry.ContextEdit ignored -> null;
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
        // 批 13（T10）：pi cache-warmer.ts:449-453 逐字（经格式化族共享），
        // kind 判据（interactive-mode.ts:3401/:4030）既有不动。
        return new System(
            com.pijava.coding.agent.core.CacheWarmingFormatting.formatUsage(usage),
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

    /** Join the text of every TextContent block (shared by ChatScreen, formerly duplicated). */
    public static String joinText(List<ContentBlock> blocks) {
        var builder = new StringBuilder();
        for (var block : blocks) {
            if (block instanceof ContentBlock.TextContent text) {
                builder.append(text.text());
            }
        }
        return builder.toString();
    }
}
