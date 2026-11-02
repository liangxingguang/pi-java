package com.pijava.ai.provider.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * pi {@code openRouterCost}（{@code openrouter-api.ts:40-73}）：per-token 字符串 × 1e6，
 * 缺失/负数/非数字按 fallback。
 */
class OpenRouterCostTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode node(String json) throws Exception {
        return JSON.readTree(json);
    }

    @Test
    void missingPricingIsFree() {
        assertThat(OpenRouterCost.fromWire(null).inputPrice()).isZero();
        assertThat(OpenRouterCost.fromWire(null).outputPrice()).isZero();
        assertThat(OpenRouterCost.fromWire(null).cacheReadPrice()).isZero();
        assertThat(OpenRouterCost.fromWire(null).cacheWritePrice()).isZero();
    }

    @Test
    void perTokenStringsBecomePerMillionNumbers() throws Exception {
        var pricing = OpenRouterCost.fromWire(node("""
            {"prompt":"0.0000025","completion":"0.00001",
             "input_cache_read":"0.00000025","input_cache_write":"0.000003125"}
            """));

        assertThat(pricing.inputPrice()).isEqualTo(2.5);
        assertThat(pricing.outputPrice()).isEqualTo(10);
        assertThat(pricing.cacheReadPrice()).isEqualTo(0.25);
        assertThat(pricing.cacheWritePrice()).isEqualTo(3.125);
    }

    @Test
    void negativeAndNonNumericValuesFallBackToZero() throws Exception {
        var pricing = OpenRouterCost.fromWire(node("""
            {"prompt":"-1","completion":"not-a-number","input_cache_read":"-1"}
            """));

        assertThat(pricing.inputPrice()).isZero();
        assertThat(pricing.outputPrice()).isZero();
        assertThat(pricing.cacheReadPrice()).isZero();
        assertThat(pricing.cacheWritePrice()).isZero();
    }

    @Test
    void contextTiersAreMappedAndMissingRatesInheritTheBase() throws Exception {
        var pricing = OpenRouterCost.fromWire(node("""
            {"prompt":"0.000000195","completion":"0.000000975",
             "overrides":[{"min_prompt_tokens":32000,"prompt":"0.000000325"},
                          {"min_prompt_tokens":128000,"prompt":"0.00000052","completion":"0.0000026"}]}
            """));

        assertThat(pricing.tiers()).hasSize(2);
        var first = pricing.tiers().get(0);
        assertThat(first.inputTokensAbove()).isEqualTo(32_000);
        assertThat(first.inputPrice()).isEqualTo(0.325);
        assertThat(first.outputPrice()).isEqualTo(0.975);
        assertThat(first.cacheReadPrice()).isZero();
        assertThat(first.cacheWritePrice()).isZero();
        var second = pricing.tiers().get(1);
        assertThat(second.inputPrice()).isEqualTo(0.52);
        assertThat(second.outputPrice()).isEqualTo(2.6);
    }

    @Test
    void timeBasedOverridesAreSkipped() throws Exception {
        var pricing = OpenRouterCost.fromWire(node("""
            {"prompt":"0.000001","completion":"0.000002",
             "overrides":[{"min_prompt_tokens":1000,"utc_start":"2026-01-01","prompt":"0.5"}]}
            """));

        assertThat(pricing.tiers()).isEmpty();
    }

    @Test
    void ratesAreRoundedToSixDecimals() throws Exception {
        var pricing = OpenRouterCost.fromWire(node("""
            {"prompt":"0.0000001234567","completion":"0.000001"}
            """));

        assertThat(pricing.inputPrice()).isEqualTo(0.123457);
    }
}
