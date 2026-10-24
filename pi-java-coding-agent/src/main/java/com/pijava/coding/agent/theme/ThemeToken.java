package com.pijava.coding.agent.theme;

/**
 * 主题令牌表（pi {@code theme-tokens.ts:24-101} 逐字，批 21/docs/32 T5）——57 个
 * 命名色槽，序与 pi 表序一致（fallback 填平与导出序敏感）。
 *
 * <p>纯常量闭集 ⇒ enum；5 个可选令牌带 {@link #fallback()}（同槽位，
 * pi {@code defineThemeTokens} 的校验在 static 块复刻）。</p>
 */
public enum ThemeToken {
    /** Primary accent color (logo, selected items, cursor). */
    ACCENT(Slot.FOREGROUND),
    /** Normal borders. */
    BORDER(Slot.FOREGROUND),
    /** Highlighted borders. */
    BORDER_ACCENT(Slot.FOREGROUND),
    /** Subtle borders. */
    BORDER_MUTED(Slot.FOREGROUND),
    /** Success states. */
    SUCCESS(Slot.FOREGROUND),
    /** Error states. */
    ERROR(Slot.FOREGROUND),
    /** Warning states. */
    WARNING(Slot.FOREGROUND),
    /** Secondary/dimmed text. */
    MUTED(Slot.FOREGROUND),
    /** Very dimmed text (more subtle than muted). */
    DIM(Slot.FOREGROUND),
    /** Default text color (usually empty string). */
    TEXT(Slot.FOREGROUND),
    /** Thinking block text color. */
    THINKING_TEXT(Slot.FOREGROUND),
    /** Fullscreen scrollbar track foreground (falls back to muted when omitted). */
    SCROLLBAR_TRACK(Slot.FOREGROUND, MUTED),
    /** Fullscreen scrollbar thumb foreground (falls back to text when omitted). */
    SCROLLBAR_THUMB(Slot.FOREGROUND, TEXT),
    /** Selected item background. */
    SELECTED_BG(Slot.BACKGROUND),
    /** Transcript search match background and current-match text (falls back to selectedBg). */
    SEARCH_MATCH_BG(Slot.BACKGROUND, SELECTED_BG),
    /** Transcript search match text and current-match background (falls back to text). */
    SEARCH_MATCH_TEXT(Slot.FOREGROUND, TEXT),
    /** User message background. */
    USER_MESSAGE_BG(Slot.BACKGROUND),
    /** User message text color. */
    USER_MESSAGE_TEXT(Slot.FOREGROUND),
    /** Custom message background (hook-injected messages). */
    CUSTOM_MESSAGE_BG(Slot.BACKGROUND),
    /** Custom message text color. */
    CUSTOM_MESSAGE_TEXT(Slot.FOREGROUND),
    /** Custom message type label color. */
    CUSTOM_MESSAGE_LABEL(Slot.FOREGROUND),
    /** Tool execution box (pending state). */
    TOOL_PENDING_BG(Slot.BACKGROUND),
    /** Tool execution box (success state). */
    TOOL_SUCCESS_BG(Slot.BACKGROUND),
    /** Tool execution box (error state). */
    TOOL_ERROR_BG(Slot.BACKGROUND),
    /** Tool execution box title color. */
    TOOL_TITLE(Slot.FOREGROUND),
    /** Tool execution box output text color. */
    TOOL_OUTPUT(Slot.FOREGROUND),
    /** Markdown heading text. */
    MD_HEADING(Slot.FOREGROUND),
    /** Markdown link text. */
    MD_LINK(Slot.FOREGROUND),
    /** Markdown link URL. */
    MD_LINK_URL(Slot.FOREGROUND),
    /** Markdown inline code. */
    MD_CODE(Slot.FOREGROUND),
    /** Markdown code block content. */
    MD_CODE_BLOCK(Slot.FOREGROUND),
    /** Markdown code block fences. */
    MD_CODE_BLOCK_BORDER(Slot.FOREGROUND),
    /** Markdown blockquote text. */
    MD_QUOTE(Slot.FOREGROUND),
    /** Markdown blockquote border. */
    MD_QUOTE_BORDER(Slot.FOREGROUND),
    /** Markdown horizontal rule. */
    MD_HR(Slot.FOREGROUND),
    /** Markdown list bullets/numbers. */
    MD_LIST_BULLET(Slot.FOREGROUND),
    /** Added lines in tool diffs. */
    TOOL_DIFF_ADDED(Slot.FOREGROUND),
    /** Removed lines in tool diffs. */
    TOOL_DIFF_REMOVED(Slot.FOREGROUND),
    /** Context lines in tool diffs. */
    TOOL_DIFF_CONTEXT(Slot.FOREGROUND),
    /** Syntax highlighting: comments. */
    SYNTAX_COMMENT(Slot.FOREGROUND),
    /** Syntax highlighting: keywords. */
    SYNTAX_KEYWORD(Slot.FOREGROUND),
    /** Syntax highlighting: function names. */
    SYNTAX_FUNCTION(Slot.FOREGROUND),
    /** Syntax highlighting: variable names. */
    SYNTAX_VARIABLE(Slot.FOREGROUND),
    /** Syntax highlighting: string literals. */
    SYNTAX_STRING(Slot.FOREGROUND),
    /** Syntax highlighting: number literals. */
    SYNTAX_NUMBER(Slot.FOREGROUND),
    /** Syntax highlighting: type names. */
    SYNTAX_TYPE(Slot.FOREGROUND),
    /** Syntax highlighting: operators. */
    SYNTAX_OPERATOR(Slot.FOREGROUND),
    /** Syntax highlighting: punctuation. */
    SYNTAX_PUNCTUATION(Slot.FOREGROUND),
    /** Thinking level border: off. */
    THINKING_OFF(Slot.FOREGROUND),
    /** Thinking level border: minimal. */
    THINKING_MINIMAL(Slot.FOREGROUND),
    /** Thinking level border: low. */
    THINKING_LOW(Slot.FOREGROUND),
    /** Thinking level border: medium. */
    THINKING_MEDIUM(Slot.FOREGROUND),
    /** Thinking level border: high. */
    THINKING_HIGH(Slot.FOREGROUND),
    /** Thinking level border: xhigh. */
    THINKING_XHIGH(Slot.FOREGROUND),
    /** Thinking level border: max (falls back to thinkingXhigh when omitted). */
    THINKING_MAX(Slot.FOREGROUND, THINKING_XHIGH),
    /** Editor border color in bash mode. */
    BASH_MODE(Slot.FOREGROUND);

    /** 令牌所属槽位——{@code ""}（终端默认色）按槽位分别指默认前景/背景。 */
    public enum Slot {
        /** 前景槽。 */ FOREGROUND,
        /** 背景槽。 */ BACKGROUND
    }

    private final Slot slot;
    private final ThemeToken fallback;

    ThemeToken(Slot slot) {
        this(slot, null);
    }

    ThemeToken(Slot slot, ThemeToken fallback) {
        this.slot = slot;
        this.fallback = fallback;
    }

    /** 令牌槽位。 */
    public Slot slot() {
        return slot;
    }

    /** 缺席时的回落令牌（5 个可选令牌非空）。 */
    public ThemeToken fallback() {
        return fallback;
    }

    static {
        // pi defineThemeTokens（theme-tokens.ts:9-21）：fallback 存在且同槽位。
        for (var token : values()) {
            if (token.fallback == null) {
                continue;
            }
            if (token.fallback.slot != token.slot) {
                throw new IllegalStateException("Theme token " + token + " has fallback "
                    + token.fallback + " in a different color slot");
            }
        }
    }

    /** pi 令牌名（camelCase）⇒ 常量；未知 ⇒ null。 */
    public static ThemeToken fromPiName(String name) {
        return switch (name) {
            case "accent" -> ACCENT;
            case "border" -> BORDER;
            case "borderAccent" -> BORDER_ACCENT;
            case "borderMuted" -> BORDER_MUTED;
            case "success" -> SUCCESS;
            case "error" -> ERROR;
            case "warning" -> WARNING;
            case "muted" -> MUTED;
            case "dim" -> DIM;
            case "text" -> TEXT;
            case "thinkingText" -> THINKING_TEXT;
            case "scrollbarTrack" -> SCROLLBAR_TRACK;
            case "scrollbarThumb" -> SCROLLBAR_THUMB;
            case "selectedBg" -> SELECTED_BG;
            case "searchMatchBg" -> SEARCH_MATCH_BG;
            case "searchMatchText" -> SEARCH_MATCH_TEXT;
            case "userMessageBg" -> USER_MESSAGE_BG;
            case "userMessageText" -> USER_MESSAGE_TEXT;
            case "customMessageBg" -> CUSTOM_MESSAGE_BG;
            case "customMessageText" -> CUSTOM_MESSAGE_TEXT;
            case "customMessageLabel" -> CUSTOM_MESSAGE_LABEL;
            case "toolPendingBg" -> TOOL_PENDING_BG;
            case "toolSuccessBg" -> TOOL_SUCCESS_BG;
            case "toolErrorBg" -> TOOL_ERROR_BG;
            case "toolTitle" -> TOOL_TITLE;
            case "toolOutput" -> TOOL_OUTPUT;
            case "mdHeading" -> MD_HEADING;
            case "mdLink" -> MD_LINK;
            case "mdLinkUrl" -> MD_LINK_URL;
            case "mdCode" -> MD_CODE;
            case "mdCodeBlock" -> MD_CODE_BLOCK;
            case "mdCodeBlockBorder" -> MD_CODE_BLOCK_BORDER;
            case "mdQuote" -> MD_QUOTE;
            case "mdQuoteBorder" -> MD_QUOTE_BORDER;
            case "mdHr" -> MD_HR;
            case "mdListBullet" -> MD_LIST_BULLET;
            case "toolDiffAdded" -> TOOL_DIFF_ADDED;
            case "toolDiffRemoved" -> TOOL_DIFF_REMOVED;
            case "toolDiffContext" -> TOOL_DIFF_CONTEXT;
            case "syntaxComment" -> SYNTAX_COMMENT;
            case "syntaxKeyword" -> SYNTAX_KEYWORD;
            case "syntaxFunction" -> SYNTAX_FUNCTION;
            case "syntaxVariable" -> SYNTAX_VARIABLE;
            case "syntaxString" -> SYNTAX_STRING;
            case "syntaxNumber" -> SYNTAX_NUMBER;
            case "syntaxType" -> SYNTAX_TYPE;
            case "syntaxOperator" -> SYNTAX_OPERATOR;
            case "syntaxPunctuation" -> SYNTAX_PUNCTUATION;
            case "thinkingOff" -> THINKING_OFF;
            case "thinkingMinimal" -> THINKING_MINIMAL;
            case "thinkingLow" -> THINKING_LOW;
            case "thinkingMedium" -> THINKING_MEDIUM;
            case "thinkingHigh" -> THINKING_HIGH;
            case "thinkingXhigh" -> THINKING_XHIGH;
            case "thinkingMax" -> THINKING_MAX;
            case "bashMode" -> BASH_MODE;
            default -> null;
        };
    }

    /** 常量 ⇒ pi 令牌名（camelCase，导出/错误文案用）。 */
    public String piName() {
        return switch (this) {
            case ACCENT -> "accent";
            case BORDER -> "border";
            case BORDER_ACCENT -> "borderAccent";
            case BORDER_MUTED -> "borderMuted";
            case SUCCESS -> "success";
            case ERROR -> "error";
            case WARNING -> "warning";
            case MUTED -> "muted";
            case DIM -> "dim";
            case TEXT -> "text";
            case THINKING_TEXT -> "thinkingText";
            case SCROLLBAR_TRACK -> "scrollbarTrack";
            case SCROLLBAR_THUMB -> "scrollbarThumb";
            case SELECTED_BG -> "selectedBg";
            case SEARCH_MATCH_BG -> "searchMatchBg";
            case SEARCH_MATCH_TEXT -> "searchMatchText";
            case USER_MESSAGE_BG -> "userMessageBg";
            case USER_MESSAGE_TEXT -> "userMessageText";
            case CUSTOM_MESSAGE_BG -> "customMessageBg";
            case CUSTOM_MESSAGE_TEXT -> "customMessageText";
            case CUSTOM_MESSAGE_LABEL -> "customMessageLabel";
            case TOOL_PENDING_BG -> "toolPendingBg";
            case TOOL_SUCCESS_BG -> "toolSuccessBg";
            case TOOL_ERROR_BG -> "toolErrorBg";
            case TOOL_TITLE -> "toolTitle";
            case TOOL_OUTPUT -> "toolOutput";
            case MD_HEADING -> "mdHeading";
            case MD_LINK -> "mdLink";
            case MD_LINK_URL -> "mdLinkUrl";
            case MD_CODE -> "mdCode";
            case MD_CODE_BLOCK -> "mdCodeBlock";
            case MD_CODE_BLOCK_BORDER -> "mdCodeBlockBorder";
            case MD_QUOTE -> "mdQuote";
            case MD_QUOTE_BORDER -> "mdQuoteBorder";
            case MD_HR -> "mdHr";
            case MD_LIST_BULLET -> "mdListBullet";
            case TOOL_DIFF_ADDED -> "toolDiffAdded";
            case TOOL_DIFF_REMOVED -> "toolDiffRemoved";
            case TOOL_DIFF_CONTEXT -> "toolDiffContext";
            case SYNTAX_COMMENT -> "syntaxComment";
            case SYNTAX_KEYWORD -> "syntaxKeyword";
            case SYNTAX_FUNCTION -> "syntaxFunction";
            case SYNTAX_VARIABLE -> "syntaxVariable";
            case SYNTAX_STRING -> "syntaxString";
            case SYNTAX_NUMBER -> "syntaxNumber";
            case SYNTAX_TYPE -> "syntaxType";
            case SYNTAX_OPERATOR -> "syntaxOperator";
            case SYNTAX_PUNCTUATION -> "syntaxPunctuation";
            case THINKING_OFF -> "thinkingOff";
            case THINKING_MINIMAL -> "thinkingMinimal";
            case THINKING_LOW -> "thinkingLow";
            case THINKING_MEDIUM -> "thinkingMedium";
            case THINKING_HIGH -> "thinkingHigh";
            case THINKING_XHIGH -> "thinkingXhigh";
            case THINKING_MAX -> "thinkingMax";
            case BASH_MODE -> "bashMode";
        };
    }
}
