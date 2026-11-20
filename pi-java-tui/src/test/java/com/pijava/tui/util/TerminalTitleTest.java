package com.pijava.tui.util;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/65（L7 W5，map/05 R23，pi {@code terminal.ts:600-604}／{@code utils.ts:372-377}）：
 * 终端标题的 OSC 0 序列、控制字符过滤与形状。
 */
class TerminalTitleTest {

    @Test
    void controlCharacterRunsCollapseToASingleSpace() {
        assertThat(TerminalTitle.sanitize("a\u0000\u0001b")).isEqualTo("a b");
        assertThat(TerminalTitle.sanitize("x\u007f\u009fy")).isEqualTo("x y");
        assertThat(TerminalTitle.sanitize("plain \u4e2d\u6587")).isEqualTo("plain \u4e2d\u6587");
        assertThat(TerminalTitle.sanitize(null)).isEmpty();
    }

    @Test
    void titleJoinsAppSessionAndCwdBasename() {
        assertThat(TerminalTitle.title("my session", "/home/u/proj"))
            .isEqualTo("pi-java - my session - proj");
        assertThat(TerminalTitle.title(null, "/home/u/proj")).isEqualTo("pi-java - proj");
        assertThat(TerminalTitle.title("  ", null)).isEqualTo("pi-java");
    }

    @Test
    void titleSanitizesInjectedControlCharacters() {
        assertThat(TerminalTitle.title("bad\u001bname", "/p"))
            .isEqualTo("pi-java - bad name - p");
    }

    @Test
    void escapeSequenceIsOscZeroWithBel() {
        assertThat(TerminalTitle.escapeSequence("pi-java - p"))
            .isEqualTo("\u001b]0;pi-java - p\u0007");
    }

    @Test
    void keyTextCapitalizesEverySegment() {
        assertThat(TerminalTitle.capitalizeKey("ctrl+p")).isEqualTo("Ctrl+P");
        assertThat(TerminalTitle.capitalizeKey("shift+ctrl+k")).isEqualTo("Shift+Ctrl+K");
        assertThat(TerminalTitle.capitalizeKey("")).isEmpty();
    }
}
