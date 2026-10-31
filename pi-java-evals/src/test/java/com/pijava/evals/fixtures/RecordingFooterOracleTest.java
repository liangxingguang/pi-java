package com.pijava.evals.fixtures;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 32（docs/44 §8.7/D183）：footer 行为 oracle 的排序、sanitize 与截断。
 */
class RecordingFooterOracleTest {

    @Test
    void sortsKeysAndSanitizesStatusText() {
        var oracle = new RecordingFooterOracle();
        oracle.record("b", "second");
        oracle.record("a", "first\n\tline");
        assertThat(oracle.footerText(80)).isEqualTo("first line second");
    }

    @Test
    void collapsesWhitespaceAndTrims() {
        assertThat(RecordingFooterOracle.sanitizeStatusText("  a \t\n   b  "))
            .isEqualTo("a b");
    }

    @Test
    void truncatesToWidthWithEllipsis() {
        assertThat(RecordingFooterOracle.truncateToWidth("abcdefghij", 6))
            .isEqualTo("abc...");
        assertThat(RecordingFooterOracle.truncateToWidth("abc", 6)).isEqualTo("abc");
        assertThat(RecordingFooterOracle.truncateToWidth("abcdef", 3)).isEqualTo("...");
    }

    @Test
    void emptyStatusesRenderNothing() {
        assertThat(new RecordingFooterOracle().footerText(80)).isNull();
        var oracle = new RecordingFooterOracle();
        oracle.record("a", "x");
        oracle.record("a", null);
        assertThat(oracle.footerText(80)).isNull();
    }
}
