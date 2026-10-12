package com.pijava.ai.message;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 1 ⑥（B119/B121）commit 1：{@link StopReason} 闭集枚举的<b>孤立</b>夹具——
 * 此刻它还没有任何生产消费者，这里钉住 pi {@code types.ts:469} 的七值词表、
 * wire 字面、解码容错三态与 Jackson 往返（含遗产 {@code "tool_use"}）。
 */
class StopReasonTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void wireVocabularyMatchesPiUnionExactly() {
        assertThat(java.util.Arrays.stream(StopReason.values()).map(StopReason::wireName))
            .as("pi types.ts:469 七值闭集，顺序也逐字一致")
            .containsExactly("pending", "stop", "length", "toolUse",
                "error", "aborted", "deferred");
    }

    @ParameterizedTest
    @EnumSource(StopReason.class)
    void canonicalWireNamesRoundTrip(StopReason reason) {
        assertThat(StopReason.fromWireOrNull(reason.wireName())).isEqualTo(reason);
    }

    @Test
    void fromWireOrNullIsLenientForNullEmptyAndUnknown() {
        assertThat(StopReason.fromWireOrNull(null)).isNull();
        assertThat(StopReason.fromWireOrNull("")).isNull();
        assertThat(StopReason.fromWireOrNull("bogus")).isNull();
        // B109：遗产 snake_case 只在 JsonlCodec/Jackson 入口归一，裸解码必须保持 null。
        assertThat(StopReason.fromWireOrNull("tool_use")).isNull();
    }

    @ParameterizedTest
    @EnumSource(StopReason.class)
    void jacksonSerializesCamelCaseLiteralAndReadsBack(StopReason reason) throws Exception {
        var json = JSON.writeValueAsString(reason);
        assertThat(json).isEqualTo("\"" + reason.wireName() + "\"");
        assertThat(JSON.readValue(json, StopReason.class)).isEqualTo(reason);
    }

    @Test
    void jacksonNormalizesLegacyToolUseLiteral() throws Exception {
        assertThat(JSON.readValue("\"tool_use\"", StopReason.class))
            .as("旧 JSONL 存档字面经 Jackson 入口归一（B109）")
            .isEqualTo(StopReason.TOOL_USE);
    }

    @Test
    void jacksonIsLenientForUnknownAndNull() throws Exception {
        // pi TS 类型运行时擦除：未知值零校验，比较全不命中 ⇔ Java null（未落定）。
        assertThat(JSON.readValue("\"end_turn\"", StopReason.class)).isNull();
        assertThat(JSON.readValue("\"\"", StopReason.class)).isNull();
        assertThat(JSON.readValue("null", StopReason.class)).isNull();
    }

    @Test
    void isSettledExcludesOnlyNullAndPending() {
        assertThat(StopReason.isSettled(null)).isFalse();
        assertThat(StopReason.isSettled(StopReason.PENDING)).isFalse();
        for (var reason : StopReason.values()) {
            if (reason != StopReason.PENDING) {
                assertThat(StopReason.isSettled(reason)).isTrue();
            }
        }
    }
}
