package com.pijava.tui.theme;

import dev.tamboui.style.Color;
import dev.tamboui.style.Style;

/**
 * pi 语义色令牌（{@code theme.fg("accent"/"muted"/…)}）的 java 落点
 * （docs/19 §T3，裁决⑦）：色名 → TamboUI 近似色常量。pi dark 主题的具体
 * RGB 逐字对齐属主题域事项，不在本批。
 */
public final class SemanticColors {

    private SemanticColors() {}

    /** pi {@code accent}（游标/活动路径/折叠标记/user 前缀）。 */
    public static final Style ACCENT = Style.create().cyan();
    /** pi {@code muted}（辅助文案/(aborted)/(no content)/toolResult 行）。 */
    public static final Style MUTED = Style.create().gray();
    /** pi {@code warning}（label 标记/branch summary 前缀）。 */
    public static final Style WARNING = Style.create().yellow();
    /** pi {@code success}（assistant 前缀）。 */
    public static final Style SUCCESS = Style.create().green();
    /** pi {@code error}（errorMessage 行）。 */
    public static final Style ERROR = Style.create().red();
    /** pi {@code borderAccent}（compaction 行）。 */
    public static final Style BORDER_ACCENT = Style.create().magenta();
    /** pi {@code customMessageLabel}。 */
    public static final Style CUSTOM_MESSAGE_LABEL = Style.create().cyan();
    /** pi {@code dim}（树形线/model/thinking/custom/bash 行）。 */
    public static final Style DIM = Style.EMPTY.dim();
    /** 无样式（正文）。 */
    public static final Style PLAIN = Style.EMPTY;
    /** pi {@code selectedBg}（选中行底色，近似色）。 */
    public static final Color SELECTED_BG = Color.hex("#333344");

    /** 给既有样式叠选中底色（pi {@code theme.bg("selectedBg", …)} 对位）。 */
    public static Style withSelectedBg(Style base) {
        return base.bg(SELECTED_BG);
    }
}
