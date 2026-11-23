package com.pijava.tui.component;

import java.util.ArrayList;
import java.util.List;

import com.pijava.coding.agent.extension.MarkdownTransformContext.MarkdownMessageType;
import com.pijava.coding.agent.theme.ThemeToken;
import com.pijava.tui.theme.SemanticColors;
import com.pijava.tui.theme.TuiThemeBridge;
import com.pijava.tui.util.TextLayout;

import dev.tamboui.style.Style;

/**
 * L7 W2 消息卡渲染（docs/23 §4.2/§4.4）：compaction/branch/skill 三消息卡与
 * bash 卡，对位 pi 的 {@code compaction-summary-message.ts}/
 * {@code branch-summary-message.ts}/{@code skill-invocation-message.ts}/
 * {@code bash-execution.ts}（文本级方言：LogicalLine 代替组件树）。
 *
 * <p>展开态由调用方传入（全局 {@code toolsExpanded}＋K37 per-card 覆盖）。
 * 背景窄差 D：pi {@code customMessageBg} 全卡底色，文本级不铺底（label 色
 * 承担区分）。</p>
 */
final class MessageCards {

    /** pi {@code keyText("app.tools.expand")}（默认键位）。 */
    static final String EXPAND_KEY = "ctrl+o";
    /** pi bash-execution.ts:22 {@code PREVIEW_LINES}。 */
    private static final int BASH_PREVIEW_LINES = 20;

    private MessageCards() {}

    /** MessageBubble.switch 的 compaction 臂（token 数 pi 走 toLocaleString ⇒ java %,d）。 */
    static List<LogicalLine> compaction(int tokensBefore, String summary,
                                        String messageId, String timestamp, boolean expanded) {
        var tokens = String.format("%,d", tokensBefore);
        return labeledCard("compaction",
            "Compacted from " + tokens + " tokens (" + EXPAND_KEY + " to expand)",
            "**Compacted from " + tokens + " tokens**\n\n",
            summary, MarkdownMessageType.SUMMARY, messageId, timestamp, expanded);
    }

    /** MessageBubble.switch 的 branch 臂。 */
    static List<LogicalLine> branch(String summary, String messageId, String timestamp,
                                    boolean expanded) {
        return labeledCard("branch",
            "Branch summary (" + EXPAND_KEY + " to expand)",
            "**Branch Summary**\n\n",
            summary, MarkdownMessageType.SUMMARY, messageId, timestamp, expanded);
    }

    /** MessageBubble.switch 的 skill 臂；userMessage 非空 ⇒ 卡后追加 User 气泡。 */
    static List<LogicalLine> skill(String name, String content, String userMessage,
                                   String messageId, String timestamp, boolean expanded) {
        var out = new ArrayList<LogicalLine>();
        if (expanded) {
            out.add(labelLine("skill"));
            var transformed = MarkdownTransformers.applyForEntry(content,
                MarkdownMessageType.CUSTOM, messageId, timestamp);
            out.addAll(MarkdownRenderer.toLogicalLines("**" + name + "**\n\n" + transformed,
                MarkdownTheme.current(), customText()));
        } else {
            out.add(new LogicalLine("[bold]" + labelEscaped("skill") + "[/] " + escape(name)
                + " ([dim]" + EXPAND_KEY + "[/] to expand)", 0, 0, false, customText()));
        }
        if (userMessage != null) {
            out.addAll(MessageBubble.lines(new ChatMessage.User(userMessage)));
        }
        return List.copyOf(out);
    }

    /** MessageBubble.switch 的 bash 臂。 */
    static List<LogicalLine> bash(String command, String output, Integer exitCode,
                                  boolean cancelled, boolean truncated, String fullOutputPath,
                                  boolean excludeFromContext, boolean expanded) {
        var out = new ArrayList<LogicalLine>();
        var headStyle = excludeFromContext
            ? Style.EMPTY.dim()
            : SemanticColors.accent().bold();
        out.add(new LogicalLine("$ " + escape(command == null ? "" : command), 0, 0, true, headStyle));

        var availableLines = output == null || output.isEmpty()
            ? List.<String>of()
            : List.of(output.split("\r?\n", -1));
        if (!availableLines.isEmpty()) {
            out.add(new LogicalLine("", 0, 0, false, Style.EMPTY));
            int from = expanded ? 0 : Math.max(0, availableLines.size() - BASH_PREVIEW_LINES);
            for (int i = from; i < availableLines.size(); i++) {
                out.add(new LogicalLine(escape(availableLines.get(i)), 0, 0, true,
                    SemanticColors.muted()));
            }
        }

        // 状态行（pi 的 statusParts，逐字；hiddenLineCount 按折叠窗口 20 行计）。
        var status = new ArrayList<String>();
        int hidden = availableLines.size() - Math.min(BASH_PREVIEW_LINES, availableLines.size());
        if (hidden > 0) {
            status.add(expanded
                ? "(" + EXPAND_KEY + " to collapse)"
                : "... " + hidden + " more lines (" + EXPAND_KEY + " to expand)");
        }
        if (cancelled) {
            status.add("(cancelled)");
        } else if (exitCode != null && exitCode != 0) {
            status.add("(exit " + exitCode + ")");
        }
        if (truncated && fullOutputPath != null && !fullOutputPath.isEmpty()) {
            status.add("Output truncated. Full output: " + fullOutputPath);
        }
        if (!status.isEmpty()) {
            out.add(new LogicalLine("", 0, 0, false, Style.EMPTY));
            for (var part : status) {
                out.add(new LogicalLine(escape(part), 0, 0, false, statusStyle(part)));
            }
        }
        return List.copyOf(out);
    }

    // ── 内部 ──────────────────────────────────────────────────────────

    /**
     * compaction/branch 两卡的同构渲染（两 ts 文件的 updateDisplay）：collapsed＝
     * label 行＋单行提示；expanded＝label 行＋「pi 头＋变换后正文」的 Markdown。
     */
    private static List<LogicalLine> labeledCard(String label, String collapsedText,
                                                 String markdownHeader, String body,
                                                 MarkdownMessageType messageType,
                                                 String messageId, String timestamp, boolean expanded) {
        var out = new ArrayList<LogicalLine>();
        out.add(labelLine(label));
        if (expanded) {
            // pi：变换链只见正文，头在变换结果前拼（transform(markdown) 前置 header）。
            var transformed = MarkdownTransformers.applyForEntry(body, messageType, messageId, timestamp);
            out.addAll(MarkdownRenderer.toLogicalLines(markdownHeader + transformed,
                MarkdownTheme.current(), customText()));
        } else {
            out.add(new LogicalLine(collapsedMarkup(collapsedText), 0, 0, false, customText()));
        }
        return List.copyOf(out);
    }

    /** collapsed 提示行：文本段转义、键名段夹 {@code [dim]} 标签（先转义后拼标签）。 */
    private static String collapsedMarkup(String collapsedText) {
        int keyStart = collapsedText.indexOf(EXPAND_KEY);
        if (keyStart < 0) {
            return escape(collapsedText);
        }
        return escape(collapsedText.substring(0, keyStart))
            + "[dim]" + EXPAND_KEY + "[/]"
            + escape(collapsedText.substring(keyStart + EXPAND_KEY.length()));
    }

    /** label 行（pi {@code theme.fg("customMessageLabel", bold("[x]"))}）。 */
    private static LogicalLine labelLine(String label) {
        return new LogicalLine("[bold]" + labelEscaped(label) + "[/]", 0, 0, false,
            SemanticColors.customMessageLabel());
    }

    /** 字面量 {@code [x]} 的转义形。 */
    private static String labelEscaped(String label) {
        return TextLayout.escapeMarkup("[" + label + "]");
    }

    /** pi {@code theme.fg("customMessageText", …)}。 */
    private static Style customText() {
        return TuiThemeBridge.fg(ThemeToken.CUSTOM_MESSAGE_TEXT);
    }

    private static String escape(String text) {
        return TextLayout.escapeMarkup(text);
    }

    /** 状态行配色：cancelled/截断 ⇒ warning，exit ⇒ error，提示 ⇒ muted。 */
    private static Style statusStyle(String part) {
        if (part.startsWith("(exit ")) {
            return SemanticColors.error();
        }
        if (part.equals("(cancelled)") || part.startsWith("Output truncated.")) {
            return SemanticColors.warning();
        }
        return SemanticColors.muted();
    }
}
