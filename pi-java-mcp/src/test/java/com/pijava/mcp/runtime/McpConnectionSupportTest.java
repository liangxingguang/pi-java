package com.pijava.mcp.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B220：stderr tail 的字符集边界必须与 pi 的 JS {@code trim()}／{@code sliceTail()}
 * 逐字一致，尤其窄角空格（U+202F）等 Java {@code strip()} 不处理的空白。
 */
class McpConnectionSupportTest {

    @Test
    void trimMatchesEcmaWhitespaceSet() {
        // WhiteSpace：TAB/VT/FF/SP/NBSP/BOM/全部 Zs（含 figure space 与 narrow NBSP）。
        assertThat(McpConnectionSupport.trim(
                "\t\n\u000B\f\r \u00A0\u1680\u2000\u2003\u2007\u200A\u202F\u205F\u3000\uFEFF"
                    + "error text"
                    + "\u3000\u202F\u2007\u00A0 \r\n"))
            .isEqualTo("error text");
    }

    @Test
    void trimStripsLineSeparators() {
        assertThat(McpConnectionSupport.trim("\u2028\u2029boom\u2028\u2029")).isEqualTo("boom");
    }

    @Test
    void trimKeepsControlCharactersThatEcmaDoesNotTreatAsWhitespace() {
        // Java strip() 会去掉 U+001C–U+001F；JS trim() 不去。U+0085（NEL）两者都不算。
        assertThat(McpConnectionSupport.trim("\u001C\u001D\u001E\u001F\u0085x\u001C\u001F"))
            .isEqualTo("\u001C\u001D\u001E\u001F\u0085x\u001C\u001F");
    }

    @Test
    void sliceTailMatchesPiExamples() {
        assertThat(McpConnectionSupport.sliceTail("abcdef", 3)).isEqualTo("def");
        assertThat(McpConnectionSupport.sliceTail("abc", 10)).isEqualTo("abc");
        assertThat(McpConnectionSupport.sliceTail("abc", 0)).isEqualTo("");
        assertThat(McpConnectionSupport.sliceTail("abc", -1)).isEqualTo("");
    }

    @Test
    void sliceTailNeverSplitsASurrogatePair() {
        String text = "a\uD83D\uDE00b"; // a😀b
        assertThat(McpConnectionSupport.sliceTail(text, 3)).isEqualTo("\uD83D\uDE00b");
        // 切口正落在代理对中间 ⇒ 整个字符让出，只保留 "b"。
        assertThat(McpConnectionSupport.sliceTail(text, 2)).isEqualTo("b");
        assertThat(McpConnectionSupport.sliceTail(text, 1)).isEqualTo("b");
    }

    @Test
    void sliceTailComposesWithTrimForStderrBoundary() {
        String stderr = "\u202F\n" + "x".repeat(2000) + "\n\u00A0";
        String tail = McpConnectionSupport.sliceTail(McpConnectionSupport.trim(stderr), 2000);
        assertThat(tail).isEqualTo("x".repeat(2000));
        assertThat(McpConnectionSupport.nullIfEmpty(McpConnectionSupport.sliceTail(
                McpConnectionSupport.trim("\u00A0\u202F"), 2000))).isNull();
    }
}
