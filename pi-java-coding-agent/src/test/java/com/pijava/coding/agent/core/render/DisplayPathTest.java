package com.pijava.coding.agent.core.render;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/60（L7 W1b）：pi {@code shortenHomePath}（{@code utils/paths.ts:72-78}）
 * 的文本级对位——{@code ~} 收缩、分隔符折 {@code /}、相对路径原样。
 */
class DisplayPathTest {

    @Test
    void homePrefixBecomesTilde() {
        assertThat(DisplayPath.shorten("/home/u/src/a.txt", "/home/u")).isEqualTo("~/src/a.txt");
        assertThat(DisplayPath.shorten("/home/u", "/home/u")).isEqualTo("~");
    }

    @Test
    void windowsSeparatorsFoldToSlash() {
        assertThat(DisplayPath.shorten("C:\\Users\\u\\src\\a.txt", "C:\\Users\\u"))
            .isEqualTo("~/src/a.txt");
        assertThat(DisplayPath.shorten("C:\\Users\\u\\src\\a.txt", "c:/users/u"))
            .as("Windows 折大小写")
            .isEqualTo("~/src/a.txt");
    }

    @Test
    void pathsOutsideHomeOrRelativeStayUntouched() {
        assertThat(DisplayPath.shorten("src/a.txt", "/home/u")).isEqualTo("src/a.txt");
        assertThat(DisplayPath.shorten("/home/uu/a.txt", "/home/u"))
            .as("前缀必须落在分隔符边界")
            .isEqualTo("/home/uu/a.txt");
        assertThat(DisplayPath.shorten("/home/u/a.txt", "")).isEqualTo("/home/u/a.txt");
        assertThat(DisplayPath.shorten(null, "/home/u")).isEmpty();
    }
}
