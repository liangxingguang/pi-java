package com.pijava.agent.tool;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TruncationUtilsTest {

    @Test
    void truncateLineLeavesNoLoneSurrogate() {
        // 切口落在 emoji 代理对中间时把整个字符留在切口外（pi sliceHead，truncate.ts:282-289）。
        String line = "ab😀cd";
        var result = TruncationUtils.truncateLine(line, 3);   // 切点在第 3 单元（emoji 低代理）
        assertThat(result.text()).isEqualTo("ab... [truncated]");
        assertThat(result.wasTruncated()).isTrue();
    }

    @Test
    void sliceHeadAndTailDoNotSplitSurrogatePairs() {
        assertThat(TruncationUtils.sliceHead("ab😀cd", 3)).isEqualTo("ab");
        assertThat(TruncationUtils.sliceHead("ab😀cd", 4)).isEqualTo("ab😀");
        assertThat(TruncationUtils.sliceHead("ab😀cd", 0)).isEmpty();
        assertThat(TruncationUtils.sliceHead("ab😀cd", 99)).isEqualTo("ab😀cd");
        assertThat(TruncationUtils.sliceTail("ab😀cd", 4)).isEqualTo("😀cd");
        assertThat(TruncationUtils.sliceTail("ab😀cd", 5)).isEqualTo("b😀cd");
        assertThat(TruncationUtils.sliceTail("ab😀cd", 0)).isEmpty();
    }

    @Test
    void formatSizeBytes() {
        assertThat(TruncationUtils.formatSize(500)).isEqualTo("500B");
    }

    @Test
    void formatSizeKB() {
        assertThat(TruncationUtils.formatSize(2048)).isEqualTo("2.0KB");
    }

    @Test
    void formatSizeMB() {
        assertThat(TruncationUtils.formatSize(2_097_152)).isEqualTo("2.0MB");
    }

    @Test
    void truncateHeadNoTruncationNeeded() {
        var result = TruncationUtils.truncateHead("short text", 100, 100000);
        assertThat(result.truncated()).isFalse();
        assertThat(result.content()).isEqualTo("short text");
    }

    @Test
    void truncateHeadByLines() {
        var content = "line1\nline2\nline3\nline4\nline5";
        var result = TruncationUtils.truncateHead(content, 3, 100000);
        assertThat(result.truncated()).isTrue();
        assertThat(result.truncatedBy()).isEqualTo("lines");
        assertThat(result.outputLines()).isEqualTo(3);
    }

    @Test
    void truncateTailKeepsEnd() {
        var content = "line1\nline2\nline3\nline4\nline5";
        var result = TruncationUtils.truncateTail(content, 3, 100000);
        assertThat(result.truncated()).isTrue();
        assertThat(result.content()).contains("line3", "line4", "line5");
    }

    @Test
    void truncateTailNoTruncation() {
        var result = TruncationUtils.truncateTail("short", 100, 100000);
        assertThat(result.truncated()).isFalse();
    }

    // ── B158：默认预算 = pi 的 50KB（truncate.ts:11-12）────────────────

    @Test
    void defaultBudgetIsPi50KB() {
        assertThat(TruncationUtils.DEFAULT_MAX_BYTES).isEqualTo(50 * 1024);
        assertThat(TruncationUtils.DEFAULT_MAX_LINES).isEqualTo(2000);
    }

    @Test
    void defaultHeadOverloadTruncatesExactlyAboveTheBudget() {
        var exact = "x".repeat((int) TruncationUtils.DEFAULT_MAX_BYTES);
        assertThat(TruncationUtils.truncateHead(exact).truncated())
            .as("51200 字节整：不截").isFalse();
        assertThat(TruncationUtils.truncateHead(exact + "y").truncated())
            .as("51201 字节：截（首行超限）").isTrue();
    }

    @Test
    void defaultTailOverloadTruncatesExactlyAboveTheBudget() {
        var exact = "x".repeat((int) TruncationUtils.DEFAULT_MAX_BYTES);
        assertThat(TruncationUtils.truncateTail(exact).truncated())
            .as("51200 字节整：不截").isFalse();
        var over = TruncationUtils.truncateTail(exact + "y");
        assertThat(over.truncated()).as("51201 字节：截（尾窗末行部分保留）").isTrue();
        assertThat(over.lastLinePartial()).isTrue();
    }
}
