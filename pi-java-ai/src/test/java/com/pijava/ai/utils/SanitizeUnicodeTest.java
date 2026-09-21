package com.pijava.ai.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * pi {@code packages/ai/src/utils/sanitize-unicode.ts} 的移植哨兵（package A0，
 * {@code docs/43}）。
 *
 * <p><b>等价性证明方式（D3）</b>：实现是手写 code-unit 循环（孤对代理没有码点，
 * {@code codePointAt} 不适用），语义正确性靠<b>把 pi 的正则原样搬进测试作
 * oracle</b> 做双向差分 —— 边界集 ＋ 种子固定的随机串。Java 支持定长 lookbehind
 * （{@code (?<!...)} 长度为 1）⇒ pi 的正则可在 Java 里逐字复现。</p>
 *
 * <p>代理字面量一律写成 {@code (char) 0x….} 而非源码级转义：javac 的 unicode
 * 转义处理先于词法分析（连注释里的反斜杠-u 都参与），显式码点可完全绕开该坑。</p>
 */
class SanitizeUnicodeTest {

    /**
     * pi 的正则（sanitize-unicode.ts:24）逐字搬进 Java —— 差分 oracle。
     * 字符类里用正则引擎自己的反斜杠-u 转义（源码里写成双反斜杠）。
     */
    private static final Pattern PI_REGEX = Pattern.compile(
        "[\\uD800-\\uDBFF](?![\\uDC00-\\uDFFF])|(?<![\\uD800-\\uDBFF])[\\uDC00-\\uDFFF]");

    /** oracle：pi {@code sanitizeSurrogates} 的正则实现。 */
    private static String oracle(String text) {
        return PI_REGEX.matcher(text).replaceAll("");
    }

    private static final char HIGH = (char) 0xD83D;     // 高代理（🙈 的上半）
    private static final char HIGH_2 = (char) 0xD83E;   // 另一个高代理
    private static final char LOW = (char) 0xDE48;      // 低代理（🙈 的下半）
    private static final char HIGH_MAX = (char) 0xDBFF; // 高代理上界
    private static final char LOW_MIN = (char) 0xDC00;  // 低代理下界
    private static final char LOW_MAX = (char) 0xDFFF;  // 低代理上界
    private static final String EMOJI = "🙈";            // HIGH ＋ LOW，合法配对

    /** 边界集：名称 · 输入 · 显式期望（显式期望与 oracle 互为双向证据）。 */
    private record Case(String name, String input, String expected) {}

    private static final List<Case> BOUNDARY = List.of(
        new Case("empty", "", ""),
        new Case("bmp ascii", "abc", "abc"),
        new Case("bmp non-ascii", "你好 ∑∫∂√ é", "你好 ∑∫∂√ é"),
        new Case("paired emoji", EMOJI, EMOJI),
        new Case("paired multi", "Hello " + EMOJI + " World 🚀✅", "Hello " + EMOJI + " World 🚀✅"),
        new Case("lone high", String.valueOf(HIGH), ""),
        new Case("lone low", String.valueOf(LOW), ""),
        new Case("high-low-high", "" + HIGH + LOW + HIGH, EMOJI),
        new Case("low-high-low", "" + LOW + HIGH + LOW, EMOJI),
        new Case("high-high-low", "" + HIGH + HIGH + LOW, EMOJI),
        new Case("high-low-low", "" + HIGH + LOW + LOW, EMOJI),
        new Case("lone high run", "" + HIGH + HIGH_2, ""),
        new Case("lone low run", "" + LOW + LOW, ""),
        new Case("trailing lone high", "a" + HIGH, "a"),
        new Case("leading lone low", LOW + "a", "a"),
        new Case("boundary lone high", String.valueOf(HIGH_MAX), ""),
        new Case("boundary lone low", "" + LOW_MIN + LOW_MAX, ""),
        new Case("bmp around pairs", "a" + HIGH + "b" + LOW + "c", "abc"),
        new Case("pi jsdoc emoji kept", "Hello " + EMOJI + " World", "Hello " + EMOJI + " World"),
        new Case("pi jsdoc lone high dropped", "Text " + HIGH + " here", "Text  here")
    );

    @Test
    void boundarySetMatchesPiRegexOracle() {
        for (Case c : BOUNDARY) {
            String actual = SanitizeUnicode.surrogates(c.input());
            assertThat(actual).as("%s · 与 oracle 一致", c.name()).isEqualTo(oracle(c.input()));
            assertThat(actual).as("%s · 与显式期望一致", c.name()).isEqualTo(c.expected());
            assertThat(PI_REGEX.matcher(actual).find())
                .as("%s · 结果无孤对代理残留", c.name()).isFalse();
        }
    }

    @Test
    void pairedEmojiAndBmpTextSurviveVerbatim() {
        for (String clean : List.of(EMOJI, "Hello " + EMOJI + " World", "你好 ∑∫∂√",
                "🚀✅👍🤔", "Mario Zechner äußersr eventuninformiert " + EMOJI, "")) {
            assertThat(SanitizeUnicode.surrogates(clean)).as("干净串不动").isEqualTo(clean);
        }
    }

    @Test
    void randomStringsDifferentialWithFixedSeed() {
        // 字母表＝BMP ＋ 高/低代理（含边界值）；配对与孤对都由随机组合自然产生。
        char[] alphabet = {'a', '中', 'é', HIGH, HIGH_2, HIGH_MAX, LOW, LOW_MIN, LOW_MAX};
        Random random = new Random(20260922L);
        for (int n = 0; n < 5000; n++) {
            StringBuilder sb = new StringBuilder();
            int length = random.nextInt(12);
            for (int i = 0; i < length; i++) {
                sb.append(alphabet[random.nextInt(alphabet.length)]);
            }
            String input = sb.toString();
            String actual = SanitizeUnicode.surrogates(input);
            assertThat(actual).as("随机串 %d 与 oracle 一致", n).isEqualTo(oracle(input));
            assertThat(PI_REGEX.matcher(actual).find()).as("随机串 %d 无残留", n).isFalse();
            assertThat(SanitizeUnicode.surrogates(actual)).as("随机串 %d 幂等", n).isEqualTo(actual);
        }
    }
}