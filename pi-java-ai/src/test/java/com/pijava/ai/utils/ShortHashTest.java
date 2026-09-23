package com.pijava.ai.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * pi {@code packages/ai/src/utils/hash.ts} 的移植哨兵（包 B14 步 1，
 * {@code docs/47}）。
 *
 * <p>oracle 值全部用 pi 的 TS 实现经 node 逐字节生成后钉死——本测试不验
 * 「哈希好不好」，只验「与 pi 逐字节相同」：Completions（40 字符截断）与
 * Responses（{@code fc_} 前缀）把 {@code shortHash} 的出参拼进线上 id，
 * 差一个字符就是另一个 id。</p>
 */
class ShortHashTest {

    /** pi hash.ts:3 — 空串：两个种子原值过雪崩轮。 */
    @Test
    void emptyString() {
        assertThat(ShortHash.of("")).isEqualTo("k4n83c7h0j2b");
    }

    /** pi hash.ts:3 — 单字符。 */
    @Test
    void singleCharA() {
        assertThat(ShortHash.of("a")).isEqualTo("m8735310ae7sx");
    }

    /** pi hash.ts:3 — 常见 ASCII 词。 */
    @Test
    void hello() {
        assertThat(ShortHash.of("hello")).isEqualTo("1h6qa0qrowduu");
    }

    /** pi hash.ts:3 — 带竖线分隔的组合 id 形状。 */
    @Test
    void call123PipeFcId() {
        assertThat(ShortHash.of("call_123|fc_9d8e7f6a5b4c3d2e1f0a")).isEqualTo("1ny150z1w2r7mx");
    }

    /** pi hash.ts:3 — Responses 风格 {@code fc_} 前缀 id。 */
    @Test
    void fcPrefixedId() {
        assertThat(ShortHash.of("fc_9d8e7f6a5b4c3d2e1f0a")).isEqualTo("tsluie29fb5n");
    }

    /** pi hash.ts:3 — BMP 内非 ASCII（UTF-16 码元即 char）。 */
    @Test
    void cjkAndCircledDigits() {
        assertThat(ShortHash.of("中文 input ①②③")).isEqualTo("y61ww27dpp83");
    }

    /** pi hash.ts:3 — 300 次 "x"：多轮循环路径。 */
    @Test
    void xRepeated300() {
        assertThat(ShortHash.of("x".repeat(300))).isEqualTo("1ri0b25fse44j");
    }

    /** pi hash.ts:3 — Anthropic toolu_ 前缀 + 全字母表变化。 */
    @Test
    void tooluPrefixedId() {
        assertThat(ShortHash.of("toolu_01ABCdefGHIjklMNOpqrSTUvwxYZ-__99")).isEqualTo("ymrql377hcdy");
    }

    /**
     * pi hash.ts:3 — 路径分隔符混合。oracle 生成脚本里反斜杠是<b>真实字符</b>
     * （0x5C，非转义残留）——JS/TS 源码字面量 {@code '\p'} 里的反斜杠会被丢掉
     * （{\@code \p} 非识别转义，等价 {@code 'p'}），生成 oracle 时必须用
     * {@code String.fromCharCode(92)} 显式拼接。
     */
    @Test
    void pathWithNonAscii() {
        assertThat(ShortHash.of("路径/含非ASCII\\path")).isEqualTo("7x5zgx1ru4m3i");
    }

    /** pi hash.ts:3 — 仅符号（Base36 字表外形状）。 */
    @Test
    void dashesAndUnderscores() {
        assertThat(ShortHash.of("----____")).isEqualTo("1de2hcgonxz9g");
    }

    /** pi hash.ts:3 — BMP 外 emoji：成对代理 = 2 个 UTF-16 码元（非码点路径）。 */
    @Test
    void emojiPrefixed() {
        assertThat(ShortHash.of("🚀emoji")).isEqualTo("1wuhbcnr3z4vo");
    }

    /** pi hash.ts:3 — 短 toolu_ 前缀。 */
    @Test
    void shortTooluPrefix() {
        assertThat(ShortHash.of("toolu_01")).isEqualTo("fw9x8164lgyz");
    }
}
