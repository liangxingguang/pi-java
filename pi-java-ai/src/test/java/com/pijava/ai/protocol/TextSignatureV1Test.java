package com.pijava.ai.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * <b>docs/71 G2</b>：{@code TextSignatureV1} 的编解码 —— 逐行照 pi
 * {@code openai-responses-shared.ts:52-76}（{@code encodeTextSignatureV1} /
 * {@code parseTextSignature}）。
 */
class TextSignatureV1Test {

    // ── 编码 ───────────────────────────────────────────────────────────

    @Test
    void encodeWithPhase() {
        assertThat(TextSignatureV1.encode("msg_abc", "final_answer"))
            .isEqualTo("{\"v\":1,\"id\":\"msg_abc\",\"phase\":\"final_answer\"}");
    }

    /** pi 的 `if (phase) payload.phase = phase;` —— 缺席则**无键**，不是 null。 */
    @Test
    void encodeWithoutPhaseOmitsTheKey() {
        assertThat(TextSignatureV1.encode("msg_abc", null))
            .isEqualTo("{\"v\":1,\"id\":\"msg_abc\"}");
    }

    // ── 解码 ───────────────────────────────────────────────────────────

    @Test
    void parseV1Json() {
        var parsed = TextSignatureV1.parse("{\"v\":1,\"id\":\"msg_abc\",\"phase\":\"commentary\"}")
            .orElseThrow();

        assertThat(parsed.id()).isEqualTo("msg_abc");
        assertThat(parsed.phase()).isEqualTo("commentary");
    }

    @Test
    void parseV1JsonWithoutPhase() {
        var parsed = TextSignatureV1.parse("{\"v\":1,\"id\":\"msg_abc\"}").orElseThrow();

        assertThat(parsed.id()).isEqualTo("msg_abc");
        assertThat(parsed.phase()).isNull();
    }

    /** 非法的 {@code phase} 值 ⇒ pi 只回 `{id}`（不让它上线）。 */
    @Test
    void parseV1JsonWithUnknownPhaseDropsThePhase() {
        var parsed = TextSignatureV1.parse("{\"v\":1,\"id\":\"msg_abc\",\"phase\":\"weird\"}")
            .orElseThrow();

        assertThat(parsed.id()).isEqualTo("msg_abc");
        assertThat(parsed.phase()).isNull();
    }

    /** legacy：裸 id 字符串原样返回。 */
    @Test
    void parseLegacyBareId() {
        assertThat(TextSignatureV1.parse("msg_legacy").orElseThrow().id())
            .isEqualTo("msg_legacy");
    }

    /** 坏 JSON ⇒ 落到 legacy 支：**整串**当 id（pi 的 catch 后 `return { id: signature }`）。 */
    @Test
    void parseMalformedJsonFallsBackToTheWholeString() {
        assertThat(TextSignatureV1.parse("{not json").orElseThrow().id())
            .isEqualTo("{not json");
    }

    /** v 不是 1 ⇒ 同样落 legacy 支（pi 的 if 不成立时不返回，继续往下走）。 */
    @Test
    void parseUnknownVersionFallsBackToTheWholeString() {
        assertThat(TextSignatureV1.parse("{\"v\":2,\"id\":\"msg_abc\"}").orElseThrow().id())
            .isEqualTo("{\"v\":2,\"id\":\"msg_abc\"}");
    }

    @Test
    void parseAbsentSignatureIsEmpty() {
        assertThat(TextSignatureV1.parse(null)).isEmpty();
        assertThat(TextSignatureV1.parse("")).isEmpty();
    }

    /** round-trip：编码后解出来必须等值（两个方向互为逆）。 */
    @Test
    void roundTrip() {
        for (String phase : new String[] {null, "commentary", "final_answer"}) {
            var parsed = TextSignatureV1.parse(TextSignatureV1.encode("msg_x", phase))
                .orElseThrow();
            assertThat(parsed.id()).isEqualTo("msg_x");
            assertThat(parsed.phase()).isEqualTo(phase);
        }
    }
}
