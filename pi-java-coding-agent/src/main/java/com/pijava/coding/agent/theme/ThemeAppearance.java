package com.pijava.coding.agent.theme;

/**
 * 主题设计面向的背景（pi {@code ThemeAppearance = TerminalTheme = "dark" | "light"}
 * 对位，批 21/docs/32 T5）。纯常量闭集 ⇒ enum。
 */
public enum ThemeAppearance {
    /** 深背景。 */
    DARK,
    /** 浅背景。 */
    LIGHT;

    /** pi 字面量 ⇒ 常量；未知/缺席 ⇒ null。 */
    public static ThemeAppearance fromPiName(String name) {
        return switch (name) {
            case "dark" -> DARK;
            case "light" -> LIGHT;
            default -> null;
        };
    }

    /** 常量 ⇒ pi 字面量。 */
    public String piName() {
        return this == DARK ? "dark" : "light";
    }
}
