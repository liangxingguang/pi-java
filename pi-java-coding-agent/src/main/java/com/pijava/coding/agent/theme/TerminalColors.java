package com.pijava.coding.agent.theme;

import java.util.List;

/**
 * 终端上报的当前主题色（pi {@code packages/tui/src/terminal-colors.ts} 的
 * {@code TerminalColors} 对位，批 21/docs/32 T4）。
 *
 * <p><b>裁决③（窄差 D123）</b>：TamboUI 后端没有 OSC 10/11/4 查询面，java 侧
 * 该输入恒为空实例（{@link #EMPTY}）；{@link Themes#setTerminalColors} 是留好的
 * 注入缝，后端具备能力后即通。</p>
 *
 * @param foreground 默认前景（pi OSC 10），可空
 * @param background 默认背景（pi OSC 11），可空
 * @param palette ANSI 0-15 色（pi OSC 4；仅当 16 色全上报才非空），可空
 */
public record TerminalColors(ThemeColor.Rgb foreground, ThemeColor.Rgb background,
                             List<ThemeColor.Rgb> palette) {

    /** 终端什么都没上报。 */
    public static final TerminalColors EMPTY = new TerminalColors(null, null, null);

    /** 紧凑构造：palette 防御性拷贝。 */
    public TerminalColors {
        palette = palette == null ? null : List.copyOf(palette);
    }
}
