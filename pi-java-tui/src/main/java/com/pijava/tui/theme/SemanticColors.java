package com.pijava.tui.theme;

import com.pijava.coding.agent.theme.ThemeToken;
import com.pijava.coding.agent.theme.Themes;

import dev.tamboui.style.Color;
import dev.tamboui.style.Style;

/**
 * pi 语义色令牌（{@code theme.fg("accent"/"muted"/…)}）的 java 落点。
 *
 * <p><b>批 21（docs/32 T10，裁决⑨）</b>：常量字段 ⇒ 静态访问器，委托
 * {@link Themes#current()} 的活跃主题（原 TamboUI 近似色常量——cyan/gray 等——
 * 由真主题色取代；主题切换即时生效，无须重启）。访问器现读不缓存；
 * 主题未初始化时 {@code Themes.current()} 懒回落内建 dark（裁决⑤，窄差 D126）。
 * </p>
 */
public final class SemanticColors {

    private SemanticColors() {}

    /** pi {@code accent}（游标/活动路径/折叠标记/user 前缀）。 */
    public static Style accent() {
        return TuiThemeBridge.fg(ThemeToken.ACCENT);
    }

    /** pi {@code muted}（辅助文案/(aborted)/(no content)/toolResult 行）。 */
    public static Style muted() {
        return TuiThemeBridge.fg(ThemeToken.MUTED);
    }

    /** pi {@code warning}（label 标记/branch summary 前缀）。 */
    public static Style warning() {
        return TuiThemeBridge.fg(ThemeToken.WARNING);
    }

    /** pi {@code success}（assistant 前缀）。 */
    public static Style success() {
        return TuiThemeBridge.fg(ThemeToken.SUCCESS);
    }

    /** pi {@code error}（errorMessage 行）。 */
    public static Style error() {
        return TuiThemeBridge.fg(ThemeToken.ERROR);
    }

    /** pi {@code borderAccent}（compaction 行）。 */
    public static Style borderAccent() {
        return TuiThemeBridge.fg(ThemeToken.BORDER_ACCENT);
    }

    /** pi {@code customMessageLabel}。 */
    public static Style customMessageLabel() {
        return TuiThemeBridge.fg(ThemeToken.CUSTOM_MESSAGE_LABEL);
    }

    /** pi {@code dim}（树形线/model/thinking/custom/bash 行）。 */
    public static Style dim() {
        return TuiThemeBridge.fg(ThemeToken.DIM);
    }

    /** 无样式（正文）。 */
    public static Style plain() {
        return Style.EMPTY;
    }

    /** pi {@code selectedBg}（选中行底色）。 */
    public static Color selectedBg() {
        return TuiThemeBridge.tokenColor(ThemeToken.SELECTED_BG);
    }

    /** 给既有样式叠选中底色（pi {@code theme.bg("selectedBg", …)} 对位）。 */
    public static Style withSelectedBg(Style base) {
        return base.bg(selectedBg());
    }
}
