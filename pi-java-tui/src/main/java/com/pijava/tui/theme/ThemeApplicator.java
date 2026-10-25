package com.pijava.tui.theme;

import com.pijava.coding.agent.theme.Theme;
import com.pijava.coding.agent.theme.ThemeColor;
import com.pijava.coding.agent.theme.ThemeColorMath;
import com.pijava.coding.agent.theme.ThemeToken;
import com.pijava.coding.agent.theme.Themes;

import dev.tamboui.css.engine.StyleEngine;

/**
 * 活跃主题 → TamboUI {@link StyleEngine} 的桥梁（批 21/docs/32 T10，裁决⑥）。
 *
 * <p>TCSS 是 java 自有样式层（pi 无对应物——pi 组件直接烘 ANSI）；本类从
 * 活跃主题<b>生成</b>样式表内容，取代原两个手写 {@code .tcss} 资源
 * （{@code pi-dark.tcss}/{@code pi-light.tcss} 已删，裁决⑦）。映射：
 * scrollbar-thumb/track ← {@code scrollbarThumb}/{@code scrollbarTrack}，
 * EditorComponent 边框 ← {@code borderMuted}，Separator ← {@code borderMuted}。
 * pi 无 Screen/StatusBar 底色概念（终端默认背景）⇒ 生成表不含
 * {@code background} 键（行为变化注记，随闭环台账）。{@code -hover} 双臂与
 * padding 等非色键是 java-only，保留现值（窄差 D129）。</p>
 *
 * <p>色值书写保真序：{@code ""} 终端默认令牌 ⇒ 属性整条省略（TCSS 缺席＝
 * 默认色）；{@code Indexed} ⇒ {@code indexed(n)}（终端自有调色板，pi tier-3
 * 语义）；其余 ⇒ {@code #rrggbb}。</p>
 */
public final class ThemeApplicator {

    /** 生成样式表的注册名（恒唯一——切换主题＝同名人内容替换＋reload）。 */
    public static final String STYLESHEET_NAME = "theme";

    private ThemeApplicator() {}

    /** 建 engine 并应用当前活跃主题（启动点）。 */
    public static StyleEngine engineForCurrentTheme() {
        var engine = StyleEngine.create();
        loadGenerated(engine);
        engine.setActiveStylesheet(STYLESHEET_NAME);
        return engine;
    }

    /**
     * 主题切换/热重载后重应用（pi {@code invalidate + requestRender} 的样式表
     部分；组件重渲染由调用方另行触发）。
     */
    public static void reapply(StyleEngine engine) {
        // 同名 loadStylesheet 即替换＋变更通知；reloadStylesheet 只服务
        // classpath/文件来源（对生成内容会去找名为 "theme" 的资源而炸）。
        loadGenerated(engine);
    }

    /** 装载生成内容（{@code addStylesheet(name, content)} 是文本变体——
     *  {@code loadStylesheet(name, String)} 的第二参是 classpath 资源路径）。 */
    private static void loadGenerated(StyleEngine engine) {
        engine.addStylesheet(STYLESHEET_NAME, generateTcss(Themes.current()));
    }

    /** 从主题解析色生成 TCSS 文本（映射见类 javadoc）。 */
    static String generateTcss(Theme theme) {
        var out = new StringBuilder();
        out.append("""
            .ChatPanel {
                padding: 1 2;
            }
            """);

        rule(out, "ChatViewportElement-scrollbar-thumb", "foreground",
            colorLiteral(theme, ThemeToken.SCROLLBAR_THUMB));
        rule(out, "ChatViewportElement-scrollbar-track", "foreground",
            colorLiteral(theme, ThemeToken.SCROLLBAR_TRACK));
        // java-only hover 双臂（无 pi 对位，窄差 D129）——保留原硬编码值。
        rule(out, "ChatViewportElement-scrollbar-thumb-hover", "foreground", "#7aa2f7");
        rule(out, "ChatViewportElement-scrollbar-track-hover", "foreground", "#414868");
        rule(out, ".EditorComponent", "border-color",
            colorLiteral(theme, ThemeToken.BORDER_MUTED));
        rule(out, ".Separator", "background",
            colorLiteral(theme, ThemeToken.BORDER_MUTED));
        return out.toString();
    }

    private static void rule(StringBuilder out, String selector, String property, String value) {
        if (value == null) {
            // "" 终端默认 ⇒ 属性整条省略（TCSS 缺席＝终端默认色）
            return;
        }
        out.append(selector).append(" {\n    ").append(property).append(": ")
            .append(value).append(";\n}\n\n");
    }

    private static String colorLiteral(Theme theme, ThemeToken token) {
        if (theme.isTerminalDefault(token)) {
            return null;
        }
        var color = theme.colors().get(token);
        if (color == null) {
            return null;
        }
        if (color instanceof ThemeColor.Indexed indexed) {
            return "indexed(" + indexed.index() + ")";
        }
        return ThemeColorMath.colorToHex(color);
    }
}
