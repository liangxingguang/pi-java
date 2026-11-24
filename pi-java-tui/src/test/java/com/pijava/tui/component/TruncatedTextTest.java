package com.pijava.tui.component;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.pijava.tui.util.ElementRenderSupport;

/** L7 W6（C13）：truncated-text 首行/截断/padding。 */
class TruncatedTextTest {

    @Test
    void truncatesFirstLineWithEllipsisAndPads() throws Exception {
        var buffer = ElementRenderSupport.render(
            new TruncatedText("hello world\nsecond", 1, 1), 8, 3);
        assertThat(buffer.get(0, 0).symbol()).isEqualTo(" ");
        assertThat(buffer.get(0, 1).symbol()).isEqualTo(" ");
        assertThat(buffer.get(1, 1).symbol()).isEqualTo("h");
        assertThat(row(buffer, 1)).isEqualTo(" hello… ");
    }

    @Test
    void shortTextFillsExactWidth() throws Exception {
        var buffer = ElementRenderSupport.render(new TruncatedText("ab", 1, 0), 6, 2);
        assertThat(row(buffer, 0)).isEqualTo(" ab   ");
    }

    private static String row(dev.tamboui.buffer.Buffer buffer, int y) {
        var sb = new StringBuilder();
        for (int x = 0; x < buffer.width(); x++) {
            sb.append(buffer.get(x, y).symbol());
        }
        return sb.toString();
    }
}
