package com.pijava.tui.component;

import java.util.List;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.coding.agent.extension.MarkdownTransformContext.MarkdownMessageType;

/**
 * TUI-internal message model: projects agent-core {@link Entry} values into
 * flat, render-friendly bubbles (Phase 3 design §4.1).
 */
public sealed interface ChatMessage {

    record User(String text) implements ChatMessage {}

    /**
     * 助手气泡。
     *
     * <p>L5 W8（pi {@code MarkdownTransformContext.messageId}/{@code timestamp}）：
     * 从<b>持久化条目</b>投影时携带条目 id 与 ISO 时间，实时流式消息两者为
     * {@code null}（{@link #live} 便捷构造）。</p>
     */
    record Assistant(List<ContentBlock> blocks, String messageId, String timestamp)
            implements ChatMessage {

        /** 实时（非持久化）消息：无条目标识。 */
        public Assistant(List<ContentBlock> blocks) {
            this(blocks, null, null);
        }

        /** Defensive copy of the content blocks. */
        public Assistant {
            blocks = List.copyOf(blocks);
        }
    }
    record ToolCall(String name, String arguments) implements ChatMessage {}
    /**
     * Tool result content blocks (text and/or diff), for rich rendering (P6-26).
     *
     * <p>L7 W1（pi {@code render-utils.ts:146-164} 的 {@code toolTimerText} 终局臂）：
     * {@code durationMillis} 直接取自持久化 {@link Message.ToolResultMessage#durationMs()}
     * （B234，pi {@code types.ts:635}——只围 {@code execute()} 本体，未执行调用与旧盘为
     * {@code null} ≙ 键省略）。有值时结果行末追加 {@code Took X.Ys}，跨重载仍准
     * （pi 的「recorded duration wins」）。</p>
     */
    record ToolResult(List<ContentBlock> content, boolean isError, Long durationMillis,
                      String toolName) implements ChatMessage {

        /** 无计时形（{@code null} ≙ pi 的 {@code durationMs} 键省略）。 */
        public ToolResult(List<ContentBlock> content, boolean isError) {
            this(content, isError, null, null);
        }

        /** 无工具名形（docs/60 之前的构造点：渲染器查表落空 ⇒ 默认渲染）。 */
        public ToolResult(List<ContentBlock> content, boolean isError, Long durationMillis) {
            this(content, isError, durationMillis, null);
        }

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
     * L7 W2（R17；pi {@code CompactionSummaryMessageComponent}）：压缩摘要卡——
     * collapsed 单行 {@code Compacted from N tokens (… to expand)}，expanded 走
     * Markdown（{@code SUMMARY} 变换＋messageId/timestamp，漏项#6）。展开态在
     * 渲染层（全局 {@code toolsExpanded}＋K37 per-card），投影不可变。
     */
    record CompactionCard(int tokensBefore, String summary, String messageId, String timestamp)
            implements ChatMessage {}

    /** L7 W2（R18；pi {@code BranchSummaryMessageComponent}）：分支摘要卡（同上同构）。 */
    record BranchCard(String summary, String messageId, String timestamp) implements ChatMessage {}

    /**
     * L7 W2（R19；pi {@code SkillInvocationMessageComponent}）：skill 调用卡——
     * {@code <skill>} 块由 {@link SkillBlocks#parse} 解析；{@code userMessage}
     * 非空时渲染层在其后追加 User 气泡（pi 渲染两个组件）。
     */
    record SkillCard(String name, String content, String userMessage,
                     String messageId, String timestamp) implements ChatMessage {}

    /**
     * L7 W2（漏项#7；pi {@code BashExecutionComponent}）：bash 执行卡——
     * {@code $ cmd} 头＋尾部输出预览＋三态状态行（cancelled/exit/truncated）。
     * {@code excludeFromContext}（{@code !!}）⇒ dim 色对位 pi {@code bashMode}。
     */
    record BashCard(String command, String output, Integer exitCode, boolean cancelled,
                    boolean truncated, String fullOutputPath, boolean excludeFromContext)
            implements ChatMessage {}

    /**
     * 批 25（docs/36 T8）：Markdown 源文本气泡——经 {@code MarkdownRenderer}
     * 渲染（pi 的 {@code Markdown} 组件对位，/hotkeys interactive-mode.ts:6921）。
     * 消费面＝/help /hotkeys 等 markdown 源命令结果。
     */
    record Markdown(String text) implements ChatMessage {}

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
        var projected = project(entry, pad);
        return projected == null || pad <= 0 ? projected : new Padded(projected, pad);
    }

    private static ChatMessage project(Entry entry, int pad) {
        return switch (entry) {
            case Entry.Message message -> fromMessage(message);
            case Entry.ModelChange change ->
                new System("Model: " + change.provider() + "/" + change.modelId(),
                    MetaKind.MODEL_CHANGE);
            case Entry.ThinkingLevelChange change ->
                new System("Thinking: " + change.thinkingLevel(),
                    MetaKind.THINKING_LEVEL);
            // L7 W2（R17/R18＋漏项#6）：压缩/分支摘要从一行弱形状改为对位 pi 的
            // 独立卡（collapsed 单行＋expanded Markdown），变换链带条目 id/时间。
            case Entry.Compaction compaction ->
                new CompactionCard(compaction.tokensBefore(), compaction.summary(),
                    compaction.id(), iso(compaction.timestamp()));

            case Entry.BranchSummary summary ->
                new BranchCard(summary.summary(), summary.id(), iso(summary.timestamp()));
            case Entry.Custom custom -> {
                // 批 26（docs/37 T10）：pi interactive-mode.ts:3846-3866——entry
                // renderer 缺席 ⇒ 不渲（批 26 前的 "Custom event:" 兜底退役）。
                var renderer = CustomRenderers.resolveEntry(custom.customType());
                if (renderer == null) {
                    yield null;
                }
                var rendered = renderer.render(custom.customType(), custom.data(), false);
                yield rendered.isEmpty() ? null
                    : new System(String.join("\n", rendered), MetaKind.CUSTOM);
            }
            case Entry.CustomMessage customMessage -> {
                // 批 26（docs/37 T10）：pi interactive-mode.ts:3889-3902——display 门
                // 在先；renderer 缺席 ⇒ 回落默认（pi 组件内回落，java 投影层回落）。
                if (!customMessage.display()) {
                    yield null;
                }
                var renderer = CustomRenderers.resolveMessage(customMessage.customType());
                if (renderer != null) {
                    var rendered = renderer.render(customMessage.customType(),
                        customMessage.content(), customMessage.details(), false, pad);
                    if (!rendered.isEmpty()) {
                        yield new System(String.join("\n", rendered), MetaKind.CUSTOM);
                    }
                }
                // 漏项#6（pi custom-message.ts 构造尾部）：回落臂变换链带 id/时间。
                yield new System("[" + customMessage.customType() + "] "
                    + MarkdownTransformers.applyForEntry(customMessage.content().plainText(),
                        MarkdownMessageType.CUSTOM, customMessage.id(),
                        iso(customMessage.timestamp())), MetaKind.CUSTOM);
            }
            case Entry.Usage usage -> cacheWarmNotice(usage);
            // L7 W2（漏项#7，pi interactive-mode.ts bashExecution 臂）：bashExecution
            // 从纯文本 User 改为对位 BashExecutionComponent 的卡（`$ cmd`＋尾部
            // 输出预览＋状态行）；!! 条目持久化但不进上下文（excludeFromContext）。
            case Entry.BashExecution bash ->
                new BashCard(bash.command(), bash.output(), bash.exitCode(), bash.cancelled(),
                    bash.truncated(), bash.fullOutputPath(),
                    Boolean.TRUE.equals(bash.excludeFromContext()));
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
        return cacheWarmNoticeLine(usage.usage().cost().total(), usage.note(), 0);
    }

    /**
     * L5 W2（pi {@code addCacheWarmingUsage} 的组件构造，interactive-mode.ts:4361）：
     * 累计成本的暖行；实时臂合并时替换 chatPanel 末项（{@code pad} 同 {@link #from} 烘焙）。
     */
    public static ChatMessage cacheWarmNoticeLine(double totalCost, String note, int pad) {
        var line = new System(com.pijava.coding.agent.core.CacheWarmingFormatting
            .formatUsage(totalCost, note), MetaKind.GENERIC);
        return pad <= 0 ? line : new Padded(line, pad);
    }

    private static ChatMessage fromMessage(Entry.Message message) {
        var content = message.message().content();
        return switch (message.message().role()) {
            // L7 W2（R19，pi interactive-mode.ts user 臂的 parseSkillBlock）：user 消息
            // 命中 <skill> 块 ⇒ SkillCard（userMessage 在渲染层追加 User 气泡）。
            case "user" -> {
                var text = joinText(content);
                var skill = SkillBlocks.parse(text);
                if (skill != null) {
                    yield new SkillCard(skill.name(), skill.content(), skill.userMessage(),
                        message.id(), iso(message.timestamp()));
                }
                yield new User(text);
            }
            // L5 W8（pi types.ts:1562-1568）：持久化条目 ⇒ 带 id 与 ISO 时间。
            case "assistant" -> new Assistant(content, message.id(),
                message.timestamp() == null ? null : message.timestamp().toString());
            case "tool" -> fromToolBlocks(content, message.message());
            default -> new System("Unknown message role: " + message.message().role(),
                MetaKind.GENERIC);
        };
    }

    /** Entry 时间戳的 ISO 形（{@code null} ≙ pi 的 undefined）。 */
    private static String iso(java.time.Instant timestamp) {
        return timestamp == null ? null : timestamp.toString();
    }

    /**
     * 工具结果投影：{@code isError} 与 {@code durationMs} 从持久化
     * {@link Message.ToolResultMessage} 取（B234）；内嵌 {@link ContentBlock.ToolResultContent}
     * 的旧形只有内容面，包内 {@code isError} 优先、计时沿用外层。
     */
    private static ChatMessage fromToolBlocks(List<ContentBlock> blocks, Message source) {
        var tool = source instanceof Message.ToolResultMessage result ? result : null;
        var durationMs = tool == null ? null : tool.durationMs();
        var toolName = tool == null ? null : tool.toolName();
        if (blocks.isEmpty()) {
            return new ToolResult(List.of(), tool != null && tool.isError(), durationMs, toolName);
        }
        var block = blocks.get(0);
        if (block instanceof ContentBlock.ToolResultContent result) {
            return new ToolResult(result.content(), result.isError(), durationMs, result.toolName());
        }
        return new ToolResult(blocks, tool != null && tool.isError(), durationMs, toolName);
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
