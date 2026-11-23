package com.pijava.tui.component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.pijava.ai.message.ContentBlock;
import com.pijava.coding.agent.extension.MarkdownTransformContext.MarkdownMessageType;
import com.pijava.coding.agent.theme.ThemeToken;
import com.pijava.tui.theme.TuiThemeBridge;
import com.pijava.tui.util.TextLayout;

import dev.tamboui.style.Color;
import dev.tamboui.style.Style;

/**
 * Projects a {@link ChatMessage} into width-agnostic {@link LogicalLine}s
 * (Phase 3 alignment design §5.3). Wrapping happens at render time inside the
 * {@link ChatViewportElement}, so message content never caches a stale width.
 */
public final class MessageBubble {

    /** Continuation indent for user/assistant message bodies. */
    private static final int MESSAGE_INDENT = 2;
    /** Indent for tool arguments and tool results (Codex-CLI hierarchy). */
    private static final int TOOL_INDENT = 4;

    private MessageBubble() {}

    /**
     * Builds the logical lines for a chat message.
     *
     * @param msg the chat message
     * @return logical lines (empty for an empty message)
     */
    public static List<LogicalLine> lines(ChatMessage msg) {
        return lines(msg, -1, index -> null);
    }

    /**
     * L7 W2（K37）：带 per-card 展开态的投影——{@code cardExpanded} 对卡类消息
     * （三卡＋bash 卡）返回显式值（null ⇒ 回落全局 {@code toolsExpanded}）。
     *
     * @param messageIndex 消息在 chat 面板中的索引（点击切换的 key）
     */
    public static List<LogicalLine> lines(ChatMessage msg, int messageIndex,
                                          java.util.function.IntFunction<Boolean> cardExpanded) {
        return lines(msg, messageIndex, cardExpanded, null);
    }

    /**
     * L7 W3（R31）：带 Markdown 顶层块缓存的投影。{@code cache == null} ⇒ 无缓存
     * 纯函数路径（测试/scrollback 等非视口消费点），输出逐字节一致。
     */
    public static List<LogicalLine> lines(ChatMessage msg, int messageIndex,
                                          java.util.function.IntFunction<Boolean> cardExpanded,
                                          MarkdownRenderCache cache) {
        Boolean perCard = cardExpanded.apply(messageIndex);
        boolean expanded = perCard != null ? perCard : MessageBubbleVisibility.toolsExpanded();
        return switch (msg) {
            case ChatMessage.User(var text) -> prefix(
                TextLayout.split(TextLayout.escapeMarkup(text), false),
                "› ", MESSAGE_INDENT);
            case ChatMessage.Assistant(var blocks, var messageId, var timestamp) ->
                renderBlocks(blocks, messageId, timestamp, cache);
            case ChatMessage.ToolCall(var name, var arguments) -> toolCallCard(name, arguments);
            case ChatMessage.ToolResult(var content, var isError, var durationMillis, var toolName) ->
                toolResult(content, isError, durationMillis, toolName);
            case ChatMessage.Error(var message) ->
                TextLayout.split("[red]" + TextLayout.escapeMarkup(message) + "[/]", false);
            case ChatMessage.System(var text, var kind) -> system(text, kind);
            // 批 25（docs/36 T8）：markdown 源文本走渲染管线（pi Markdown 组件对位）。
            case ChatMessage.Markdown(var text) ->
                markdownLines(cache, text, Style.EMPTY);
            case ChatMessage.TurnSeparator(var label) ->
                List.of(new LogicalLine(separator(label), 0, 0, false, Style.EMPTY.dim()));
            // L7 W2（R17；pi compaction-summary-message.ts）。
            case ChatMessage.CompactionCard(var tokensBefore, var summary, var messageId, var timestamp) ->
                MessageCards.compaction(tokensBefore, summary, messageId, timestamp, expanded);
            // L7 W2（R18；pi branch-summary-message.ts）。
            case ChatMessage.BranchCard(var summary, var messageId, var timestamp) ->
                MessageCards.branch(summary, messageId, timestamp, expanded);
            // L7 W2（R19；pi skill-invocation-message.ts）：userMessage 非空 ⇒ 卡后
            // 追加 User 气泡（pi 渲染两个组件）。
            case ChatMessage.SkillCard(var name, var content, var userMessage,
                                       var messageId, var timestamp) ->
                MessageCards.skill(name, content, userMessage, messageId, timestamp, expanded);
            // L7 W2（漏项#7；pi bash-execution.ts）。
            case ChatMessage.BashCard(
                    var command, var output, var exitCode, var cancelled,
                    var truncated, var fullOutputPath, var excludeFromContext) ->
                MessageCards.bash(command, output, exitCode, cancelled, truncated,
                    fullOutputPath, excludeFromContext, expanded);
            // 批 12 T6：outputPad 烘焙包装——每个 LogicalLine 左 indent 加 pad
            // （右 pad 由 ChatViewportElement.flatten 的内容宽 −2×pad 承担）。
            case ChatMessage.Padded(var inner, var pad) ->
                indent(lines(inner, messageIndex, cardExpanded, cache), pad);
        };
    }

    /** R31：有缓存走块复用，无缓存走静态纯函数（行为一致）。 */
    private static List<LogicalLine> markdownLines(MarkdownRenderCache cache, String markdown,
                                                   Style style) {
        return cache == null
            ? MarkdownRenderer.toLogicalLines(markdown, MarkdownTheme.current(), style)
            : cache.render(markdown, MarkdownTheme.current(), style);
    }

    /** Applies the first-line prefix and the continuation indent to a block. */
    private static List<LogicalLine> prefix(
            List<LogicalLine> lines, String prefix, int indent) {
        var out = new ArrayList<LogicalLine>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            var line = lines.get(i);
            out.add(i == 0
                ? new LogicalLine(prefix + line.markup(), 0, indent,
                    line.preformatted(), line.style())
                : new LogicalLine(line.markup(), indent, indent,
                    line.preformatted(), line.style()));
        }
        return out;
    }

    /**
     * 批 25（docs/36 T7）：markdown 行的 "• " 前缀——与 {@link #prefix} 同形但
     * <b>保留各行自身 indent 结构</b>（列表悬垂/代码 preformatted 不被拍平）：
     * 首行 "• " 缀内容、续行缩进 +2；其余行整体 +2（MESSAGE_INDENT 对位）。
     */
    private static List<LogicalLine> prefixMarkdown(List<LogicalLine> lines) {
        var out = new ArrayList<LogicalLine>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            var line = lines.get(i);
            out.add(i == 0
                ? new LogicalLine("• " + line.markup(), line.initialIndent(),
                    MESSAGE_INDENT + line.subsequentIndent(),
                    line.preformatted(), line.style())
                : new LogicalLine(line.markup(), MESSAGE_INDENT + line.initialIndent(),
                    MESSAGE_INDENT + line.subsequentIndent(),
                    line.preformatted(), line.style()));
        }
        return out;
    }

    /** A dim rule spanning the separator label, or a plain rule without one. */
    private static String separator(String label) {
        String safe = label == null ? "" : TextLayout.escapeMarkup(label);
        return safe.isBlank()
            ? "─".repeat(40)
            : "─".repeat(18) + " " + safe + " " + "─".repeat(18);
    }

    private static List<LogicalLine> renderBlocks(List<ContentBlock> blocks,
                                                  String messageId, String timestamp,
                                                  MarkdownRenderCache cache) {
        var lines = new ArrayList<LogicalLine>();
        boolean prefixed = false;
        for (var block : blocks) {
            var blockLines = switch (block) {
                // 批 25（docs/36 T7，pi assistant-message.ts:110-117 对位）：助手
                // text 块走 Markdown 渲染（trim＋paddingY=0 ⇒ 无块外空行）；"• "
                // 前缀/MESSAGE_INDENT 形状保持（既有 java 形，各行 indent 结构保留）。
                case ContentBlock.TextContent(var text, _) -> {
                    // 批 26（docs/37 T11）：渲染前变换缝（pi assistant-message.ts:113-117
                    // 的 createMarkdownTransform("assistant", ...) 对位）。
                    var rendered = markdownLines(cache,
                        MarkdownTransformers.applyForEntry(text.trim(),
                            MarkdownMessageType.ASSISTANT, messageId, timestamp),
                        Style.EMPTY);
                    if (!prefixed && !rendered.isEmpty()) {
                        // The bullet belongs to the assistant prose, not to a
                        // leading tool card (which has its own status prefix).
                        rendered = prefixMarkdown(rendered);
                        prefixed = true;
                    }
                    yield rendered;
                }
                // 批 25（docs/36 T7，pi :146-162 对位）：thinking 块走 Markdown 渲染，
                // defaultTextStyle＝thinkingText 色＋italic。
                // `signature`/`redacted` ride along for replay only; the visible text is
                // already the right thing to draw (a redacted block's text is pi's
                // "[Reasoning redacted]" placeholder — anthropic-messages.ts:641).
                case ContentBlock.ThinkingContent(var text, _, _) -> {
                    if (MessageBubbleVisibility.hiddenThinking()) {
                        yield List.<LogicalLine>of();
                    }
                    // 批 26（docs/37 T11）："assistant-thinking" 型变换缝。
                    yield markdownLines(cache,
                        MarkdownTransformers.apply(text,
                            MarkdownMessageType.ASSISTANT_THINKING),
                        TuiThemeBridge.fg(ThemeToken.THINKING_TEXT).italic());
                }
                // 批 16（T4d）：渲染器责任链优先（扩展/MCP resolver），未命中 ⇒
                // 原 ToolCallCard 路径不变。renderer 产已转义文本行（裁决⑦），
                // 着色由本适配层补。
                case ContentBlock.ToolUseContent(var id, var name, var arguments, _, _, _) ->
                    toolUseLines(name, arguments);
                case ContentBlock.ToolResultContent(
                        var toolUseId, var toolName, var content, var isError) ->
                    toolResultLines(toolName, content, isError);
                case ContentBlock.ImageContent(var mediaType, var data) ->
                    List.of(new LogicalLine(
                        "[image: " + mediaType + "]", 0, 0, false, Style.EMPTY.dim()));
                case ContentBlock.UrlImageContent(var url) ->
                    List.of(new LogicalLine(
                        "[image: " + url + "]", 0, 0, false, Style.EMPTY.dim()));
                case ContentBlock.DiffContent(var diffText) ->
                    DiffView.lines(diffText);
            };
            lines.addAll(blockLines);
        }
        return lines;
    }

    private static List<LogicalLine> toolCallCard(String name, String arguments) {
        return withBg(new ToolCallCard(toolName(name),
            TextLayout.escapeMarkup(arguments), "running").lines(), ThemeToken.TOOL_PENDING_BG);
    }

    /**
     * 批 16（T4d）：ToolUse 臂——渲染器命中用其行（标题行加粗对位 pi 的
     * {@code toolTitle} bold；args 部分 pi 是 muted，文本级接口不分段 ⇒ 整行 bold
     * 首行＋dim 续行，窄差 D92 的着色粒度差），未命中回落 fallback（R12 三态 bg＋
     * R26 参数形）。批 34（K8）：expanded 改现读全局展开态（Ctrl+O 驱动，D197）。
     */
    private static List<LogicalLine> toolUseLines(String name, Map<String, Object> arguments) {
        var renderer = ToolRenderers.resolve(name);
        var rendered = renderer == null ? null : renderer.renderCall(toolName(name),
            arguments == null ? Map.of() : arguments, MessageBubbleVisibility.toolsExpanded());
        if (rendered == null) {
            return fallbackCallLines(toolName(name), arguments);
        }
        return styledFlat(rendered, 0, Style.EMPTY.bold(), Style.EMPTY.dim());
    }

    /**
     * R12＋R26（pi createToolCallFallback → formatToolCallWithArgs）：调用卡回落
     * —— {@code {"_raw": …}} 保持原 raw 文本路径（MCP 传输工件，pi 无此形）；
     * 其余走 {@link FormatToolArgs}（collapsed key=value 100 截／expanded
     * {@code   key: value} 多行）。pending 三态 bg（文本级只铺文本格＝窄差 D）。
     */
    private static List<LogicalLine> fallbackCallLines(String title, Map<String, Object> arguments) {
        if (arguments != null && arguments.size() == 1 && arguments.get("_raw") instanceof String raw) {
            return withBg(new ToolCallCard(title, TextLayout.escapeMarkup(raw), "running").lines(),
                ThemeToken.TOOL_PENDING_BG);
        }
        var lines = FormatToolArgs.lines(title, arguments, MessageBubbleVisibility.toolsExpanded());
        var out = new ArrayList<LogicalLine>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            out.add(new LogicalLine(TextLayout.escapeMarkup(lines.get(i)), 0, 0, false,
                i == 0 ? Style.EMPTY.bold() : Style.EMPTY.dim()));
        }
        return withBg(out, ThemeToken.TOOL_PENDING_BG);
    }

    /** 批 16（T4d）：ToolResult 臂——渲染器命中用其行（isError ⇒ 红，否则 dim，
     *  对位 pi 的 error/toolOutput 色），未命中回落 fallback（R12 bg＋折叠）。 */
    private static List<LogicalLine> toolResultLines(String toolName,
            List<ContentBlock> content, boolean isError) {
        var renderer = ToolRenderers.resolve(toolName);
        var rendered = renderer == null ? null : renderer.renderResult(toolName(toolName),
            joinText(content), isError, MessageBubbleVisibility.toolsExpanded());
        if (rendered == null) {
            return withBg(
                fallbackResultRows(joinText(content), isError,
                    MessageBubbleVisibility.toolsExpanded(), TOOL_INDENT),
                isError ? ThemeToken.TOOL_ERROR_BG : ThemeToken.TOOL_SUCCESS_BG);
        }
        var style = isError ? Style.EMPTY.red() : Style.EMPTY.dim();
        return styledFlat(rendered, TOOL_INDENT, style, style);
    }

    /** R12（pi {@code FALLBACK_PREVIEW_LINES}）：非错误且折叠 ⇒ 前 10 行＋省略行。 */
    private static final int FALLBACK_PREVIEW_LINES = 10;

    /**
     * 通用结果回落行（pi createResultFallback 的文本级对位）：逐行 preformatted
     * （长行由终端裁剪 ≙ pi Text 行为，不再 500 字符截断）；collapsed 且非错误
     * 且超 10 行 ⇒ 前 10 行＋{@code ... N more lines (ctrl+o to expand)}。
     */
    private static List<LogicalLine> fallbackResultRows(String text, boolean isError,
                                                        boolean expanded, int indent) {
        var out = new ArrayList<LogicalLine>();
        var lines = text.split("\r?\n", -1);
        boolean folded = !isError && !expanded && lines.length > FALLBACK_PREVIEW_LINES;
        int end = folded ? FALLBACK_PREVIEW_LINES : lines.length;
        var style = isError ? Style.EMPTY.red() : Style.EMPTY.dim();
        for (int i = 0; i < end; i++) {
            var prefix = isError && i == 0 ? "! " : "";
            out.add(new LogicalLine(prefix + TextLayout.escapeMarkup(lines[i]),
                indent, indent, true, style));
        }
        if (folded) {
            out.add(new LogicalLine("... " + (lines.length - FALLBACK_PREVIEW_LINES)
                + " more lines (" + MessageCards.EXPAND_KEY + " to expand)",
                indent, indent, false, Style.EMPTY.dim()));
        }
        return List.copyOf(out);
    }

    /** R12：行叠三态 bg（行 fg 保留，bg 覆盖——patch 语义 b 胜）。 */
    private static List<LogicalLine> withBg(List<LogicalLine> lines, ThemeToken token) {
        var bg = TuiThemeBridge.bg(token);
        var out = new ArrayList<LogicalLine>(lines.size());
        for (var line : lines) {
            out.add(new LogicalLine(line.markup(), line.initialIndent(),
                line.subsequentIndent(), line.preformatted(), line.style().patch(bg)));
        }
        return List.copyOf(out);
    }

    /** renderer 产行 → LogicalLine（首行 firstStyle、续行 restStyle，统一 indent）。 */
    private static List<LogicalLine> styledFlat(List<String> lines, int indent,
                                                Style firstStyle, Style restStyle) {
        var out = new ArrayList<LogicalLine>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            out.add(new LogicalLine(lines.get(i), indent, indent, true,
                (i == 0 ? firstStyle : restStyle)));
        }
        return out;
    }
    /** Tool card label: fall back to "tool" when the stream omits the name. */
    private static String toolName(String name) {
        return name == null || name.isBlank() ? "tool" : name;
    }

    /**
     * Renders a tool result: text blocks as indented dim/red lines, diff blocks
     * via {@link DiffView} (P6-26), and — L7 W1 — the recorded duration as a
     * trailing {@code Took X.Ys} row when the message carries one
     * ({@link ToolDurations#timerText}). Other block kinds produce no flat line.
     */
    private static List<LogicalLine> toolResult(List<ContentBlock> content, boolean isError,
                                               Long durationMillis, String toolName) {
        // docs/60：结果面优先问渲染器（内置/扩展），null ≙ 无意见 ⇒ 默认逐块渲染。
        var renderer = toolName == null ? null : ToolRenderers.resolve(toolName);
        var rendered = renderer == null ? null : renderer.renderResult(toolName,
            joinText(content), isError, MessageBubbleVisibility.toolsExpanded());
        if (rendered != null) {
            var style = isError ? Style.EMPTY.red() : Style.EMPTY.dim();
            var renderedLines = new ArrayList<>(styledFlat(rendered, TOOL_INDENT, style, style));
            addTiming(renderedLines, durationMillis);
            return List.copyOf(renderedLines);
        }
        var out = new ArrayList<LogicalLine>();
        for (var block : content) {
            switch (block) {
                case ContentBlock.TextContent(var text, _) -> out.addAll(withBg(
                    fallbackResultRows(text, isError,
                        MessageBubbleVisibility.toolsExpanded(), TOOL_INDENT),
                    isError ? ThemeToken.TOOL_ERROR_BG : ThemeToken.TOOL_SUCCESS_BG));
                case ContentBlock.DiffContent(var diff) ->
                    out.addAll(indent(DiffView.lines(diff), TOOL_INDENT));
                case ContentBlock.ImageContent(var mediaType, var data) -> out.add(new LogicalLine(
                    "[image: " + mediaType + "]", TOOL_INDENT, TOOL_INDENT, false, Style.EMPTY.dim()));
                case ContentBlock.UrlImageContent(var url) -> out.add(new LogicalLine(
                    "[image: " + url + "]", TOOL_INDENT, TOOL_INDENT, false, Style.EMPTY.dim()));
                case ContentBlock.ThinkingContent ignored -> { }
                case ContentBlock.ToolUseContent ignored -> { }
                case ContentBlock.ToolResultContent ignored -> { }
            }
        }
        addTiming(out, durationMillis);
        return List.copyOf(out);
    }

    /** L7 W1：结果行末的 {@code Took X.Ys}（{@code null} ≙ 无计时）。 */
    private static void addTiming(List<LogicalLine> out, Long durationMillis) {
        if (durationMillis != null) {
            out.add(new LogicalLine(
                "[dim]" + ToolDurations.timerText(false, durationMillis, 0L) + "[/]",
                TOOL_INDENT, TOOL_INDENT, false, Style.EMPTY.dim()));
        }
    }

    /** Shifts every line's indents by {@code indent} cells (preserves style). */
    private static List<LogicalLine> indent(List<LogicalLine> lines, int indent) {
        var out = new ArrayList<LogicalLine>(lines.size());
        for (var line : lines) {
            out.add(new LogicalLine(line.markup(), line.initialIndent() + indent,
                line.subsequentIndent() + indent, line.preformatted(), line.style()));
        }
        return out;
    }

    private static List<LogicalLine> dim(List<LogicalLine> lines) {
        var out = new ArrayList<LogicalLine>(lines.size());
        for (var line : lines) {
            out.add(new LogicalLine(line.markup(), line.initialIndent(),
                line.subsequentIndent(), line.preformatted(),
                Style.EMPTY.dim().patch(line.style())));
        }
        return out;
    }

    // ── L7 W2：消息卡（R17/R18/R19＋bash）——渲染在 {@link MessageCards}。

    /**
     * Renders a metadata/system bubble (P6-25): the kind's icon prefixes the
     * first line, and the whole block takes the kind's color — except GENERIC,
     * which stays the legacy dim line with no icon.
     */
    private static List<LogicalLine> system(String text, MetaKind kind) {
        String escaped = TextLayout.escapeMarkup(text);
        String label = kind.icon() == null ? escaped : kind.icon() + " " + escaped;
        var lines = TextLayout.split(label, false);
        return kind == MetaKind.GENERIC ? dim(lines) : colorize(lines, kind.color());
    }

    /** Tints every line with a foreground color, preserving line styles. */
    private static List<LogicalLine> colorize(List<LogicalLine> lines, Color color) {
        var out = new ArrayList<LogicalLine>(lines.size());
        for (var line : lines) {
            out.add(new LogicalLine(line.markup(), line.initialIndent(),
                line.subsequentIndent(), line.preformatted(),
                Style.EMPTY.fg(color).patch(line.style())));
        }
        return out;
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

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
