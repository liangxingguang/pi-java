package com.pijava.ai.protocol;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批1/#101：逐字对位 {@code anthropic-messages.ts:1542-1575} 的
 * {@code isAnthropicStrictUnsupportedKeyword}（11 个拒绝键、minItems 特例、format 白名单）。
 */
class AnthropicStrictKeywordsTest {

    private static boolean rejects(String key, Object value) {
        return AnthropicStrictKeywords.PREDICATE.test(key, value);
    }

    @Test
    void allUnsupportedKeywordsRejected() {
        for (var key : new String[] {
                "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf",
                "maxItems", "uniqueItems", "minContains", "maxContains",
                "minProperties", "maxProperties"}) {
            assertThat(rejects(key, 1)).as(key).isTrue();
        }
    }

    @Test
    void minItemsOnlyAllowsZeroAndOne() {
        assertThat(rejects("minItems", 0)).isFalse();
        assertThat(rejects("minItems", 1)).isFalse();
        assertThat(rejects("minItems", 1.0d)).isFalse();
        assertThat(rejects("minItems", 2)).isTrue();
        // pi `value !== 0 && value !== 1`：类型不匹配也算不等。
        assertThat(rejects("minItems", "0")).isTrue();
        assertThat(rejects("minItems", null)).isTrue();
    }

    @Test
    void formatWhitelist() {
        for (var format : new String[] {
                "date-time", "time", "date", "duration", "email",
                "hostname", "uri", "ipv4", "ipv6", "uuid"}) {
            assertThat(rejects("format", format)).as(format).isFalse();
        }
        assertThat(rejects("format", "email")).isFalse();
        assertThat(rejects("format", "byte")).isTrue();
        assertThat(rejects("format", 42)).isTrue();
        assertThat(rejects("format", null)).isTrue();
    }

    @Test
    void ordinaryKeywordsPass() {
        assertThat(rejects("type", "object")).isFalse();
        assertThat(rejects("properties", java.util.Map.of())).isFalse();
        assertThat(rejects("description", "x")).isFalse();
        assertThat(rejects("minItemsButNot", 0)).isFalse();
    }
}
