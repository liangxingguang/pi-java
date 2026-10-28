package com.pijava.tui.component;

import java.util.function.Supplier;

import com.pijava.coding.agent.theme.ThemeToken;
import com.pijava.tui.theme.TuiThemeBridge;

import dev.tamboui.style.Color;

/**
 * pi {@code MarkdownTheme}（theme.ts:1022-1060 {@code getMarkdownTheme} 对位）的
 * java 落点（批 25/docs/36 T6）：十键色＋{@code codeBlockIndent}。
 *
 * <p>色值 carriage 两路（裁决②）：块级整行样式由排放层建 {@code Style}（经
 * {@link TuiThemeBridge#fg}，dim 令牌保留 dim 修饰）；行内片段走 markup 标签，
 * fg 一律序列化 {@code [#RRGGBB]}（{@link #tag(Color)}——dim 令牌的 dim 修饰
 * 在标签路丢失，登记窄差）。</p>
 *
 * <p>{@link #current()} 现读活跃主题（主题切换即时生效，同 SemanticColors 先例）。
 * {@code codeBlockIndent} 经静态注入缝（装配层装 settings 现读供应器，
 * {@link com.pijava.coding.agent.core.SettingsCompactionAccessors#getMarkdownCodeBlockIndent()}
 * 在 coding-agent 侧）。</p>
 */
public record MarkdownTheme(
    Color heading,
    Color link,
    Color linkUrl,
    Color code,
    Color codeBlock,
    Color codeBlockBorder,
    Color quote,
    Color quoteBorder,
    Color hr,
    Color listBullet,
    String codeBlockIndent
) {

    /** pi {@code SETTINGS_DEFAULTS.markdown.codeBlockIndent}（settings-defaults.ts:49-52 逐字）。 */
    public static final String DEFAULT_CODE_BLOCK_INDENT = "  ";

    /** codeBlockIndent 注入缝（批 25/docs/36 T7）：未装配 ⇒ pi 默认两空格。 */
    private static volatile Supplier<String> codeBlockIndentSource =
        () -> DEFAULT_CODE_BLOCK_INDENT;

    /** 装配层注入 codeBlockIndent 现读供应器（{@code null} ⇒ 回落默认）。 */
    public static void installCodeBlockIndent(Supplier<String> source) {
        codeBlockIndentSource = source == null
            ? () -> DEFAULT_CODE_BLOCK_INDENT : source;
    }

    /** 活跃主题现读（{@code Themes.current()} 懒回落内建 dark——测试/非全局上下文安全）。 */
    public static MarkdownTheme current() {
        return new MarkdownTheme(
            TuiThemeBridge.tokenColor(ThemeToken.MD_HEADING),
            TuiThemeBridge.tokenColor(ThemeToken.MD_LINK),
            TuiThemeBridge.tokenColor(ThemeToken.MD_LINK_URL),
            TuiThemeBridge.tokenColor(ThemeToken.MD_CODE),
            TuiThemeBridge.tokenColor(ThemeToken.MD_CODE_BLOCK),
            TuiThemeBridge.tokenColor(ThemeToken.MD_CODE_BLOCK_BORDER),
            TuiThemeBridge.tokenColor(ThemeToken.MD_QUOTE),
            TuiThemeBridge.tokenColor(ThemeToken.MD_QUOTE_BORDER),
            TuiThemeBridge.tokenColor(ThemeToken.MD_HR),
            TuiThemeBridge.tokenColor(ThemeToken.MD_LIST_BULLET),
            codeBlockIndentSource.get());
    }

    /** 行内 markup 颜色开标签（{@code [#RRGGBB]}；闭标签恒 {@code [/]}）。 */
    public static String tag(Color color) {
        return "[" + toHex(color) + "]";
    }

    /** RGB → {@code #RRGGBB}（markup 真彩 token；Indexed 经调色板映射）。 */
    public static String toHex(Color color) {
        var rgb = color.toRgb();
        return String.format("#%02X%02X%02X", rgb.r(), rgb.g(), rgb.b());
    }
}
