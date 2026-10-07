package com.pijava.agent.tool;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code truncateMiddle}（{@code truncate.ts:288-314}）—— MCP 结果的中段截断。
 */
class TruncateMiddleTest {

    @Test
    void contentWithinTheBudgetIsReturnedUntouched() {
        var result = TruncationUtils.truncateMiddle("hello", 20);
        assertThat(result.truncated()).isFalse();
        assertThat(result.content()).isEqualTo("hello");
        assertThat(result.removedChars()).isZero();
        assertThat(result.totalBytes()).isEqualTo(5);
        assertThat(result.totalLines()).isEqualTo(1);
    }

    @Test
    void exactlyTheBudgetIsNotTruncated() {
        // pi 用 `<=`：等号算「没超」。
        var text = "x".repeat(20);
        assertThat(TruncationUtils.truncateMiddle(text, 20).truncated()).isFalse();
        assertThat(TruncationUtils.truncateMiddle(text, 19).truncated()).isTrue();
    }

    @Test
    void keepsHalfTheBudgetAtEachEndWithTheMarkerBetween() {
        var text = "abcdefghij";                       // 10 bytes
        var result = TruncationUtils.truncateMiddle(text, 4);
        // 头 2 + 尾 2，中间标记的字符数按码点。
        assertThat(result.truncated()).isTrue();
        assertThat(result.content()).isEqualTo("ab…6 chars truncated…ij");
        assertThat(result.removedChars()).isEqualTo(6);
        assertThat(result.totalBytes()).isEqualTo(10);
    }

    @Test
    void anOddBudgetGivesTheTailTheExtraByte() {
        // 头 floor(5/2)=2，尾 5-2=3。
        var result = TruncationUtils.truncateMiddle("abcdefghij", 5);
        assertThat(result.content()).isEqualTo("ab…5 chars truncated…hij");
    }

    @Test
    void neverCutsInTheMiddleOfAMultiByteCharacter() {
        // 每个「世」3 字节；预算 8 ⇒ 头段从 4 退到 3、尾段从 8 进到 9，各留一个整字。
        var result = TruncationUtils.truncateMiddle("世世世世", 8);
        assertThat(result.truncated()).isTrue();
        assertThat(result.content()).isEqualTo("世…2 chars truncated…世");
        assertThat(result.content()).doesNotContain("�");
    }

    @Test
    void aBudgetTooSmallForOneCharacterLeavesBothEndsEmpty() {
        // 预算 4、每字 3 字节：头段退到 0、尾段退到末尾 ⇒ 只剩标记。这条把「回退不越界」
        // 钉住 —— 少了任一端的回退，切点就落在一个 UTF-8 字的中间。
        var result = TruncationUtils.truncateMiddle("世世世世", 4);
        // 标记里的数是**字符**（4 个），不是字节（12 个）。
        assertThat(result.content()).isEqualTo("…4 chars truncated…");
        assertThat(result.removedChars()).isEqualTo(4);
        assertThat(result.totalBytes()).isEqualTo(12);
    }

    @Test
    void removedCharsCountsCodePointsNotUtf16Units() {
        // 每个 emoji 是 2 个 UTF-16 单元、1 个码点；中间那段 2 个 emoji ⇒ 2 而非 4。
        var result = TruncationUtils.truncateMiddle("aa😀😀bb", 4);
        assertThat(result.content()).contains("…2 chars truncated…");
        assertThat(result.removedChars()).isEqualTo(2);
    }

    @Test
    void countsLinesIgnoringATrailingNewline() {
        // 与 truncateHead/truncateTail 共用同一套行计数（末尾换行不算一行）。
        assertThat(TruncationUtils.truncateMiddle("a\nb\nc", 999).totalLines()).isEqualTo(3);
        assertThat(TruncationUtils.truncateMiddle("a\nb\nc\n", 999).totalLines()).isEqualTo(3);
        assertThat(TruncationUtils.truncateMiddle("", 999).totalLines()).isZero();
    }

    @Test
    void totalBytesCountsUtf8Bytes() {
        var result = TruncationUtils.truncateMiddle("世", 999);
        assertThat(result.totalBytes()).isEqualTo("世".getBytes(StandardCharsets.UTF_8).length);
    }
}
