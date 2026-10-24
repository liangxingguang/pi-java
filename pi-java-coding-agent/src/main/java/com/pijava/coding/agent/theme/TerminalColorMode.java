package com.pijava.coding.agent.theme;

/**
 * 终端色彩能力档（pi {@code TerminalColorMode = "256color" | "truecolor"} 对位，
 * 批 21/docs/32 T4）。纯常量闭集 ⇒ enum（CLAUDE.md 编码规范）。
 */
public enum TerminalColorMode {
    /** 256 色调色板（RGB 色量化到 ANSI 索引）。 */
    COLOR_256,
    /** 24 位真彩。 */
    TRUECOLOR
}
