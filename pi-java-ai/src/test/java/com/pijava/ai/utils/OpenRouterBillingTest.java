package com.pijava.ai.utils;

import java.util.LinkedHashMap;
import java.util.Map;

import com.pijava.ai.Usage;
import com.pijava.ai.api.ApiOptions;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * docs/15（B267）：OpenRouter 账单的纯函数钉 —— pi {@code utils/openrouter-cost.ts:24-54}
 * 与 {@code test/openrouter-cost.test.ts:11-77} 的移植。
 */
class OpenRouterBillingTest {

    // ═══════════════════════════════════════════════════════════
    // isOpenRouterModel
    // ═══════════════════════════════════════════════════════════

    @Test
    void openRouterProviderNameAlwaysMatches() {
        assertThat(OpenRouterBilling.isOpenRouterModel("openrouter", null)).isTrue();
        assertThat(OpenRouterBilling.isOpenRouterModel("openrouter", "https://relay.example.com"))
            .isTrue();
    }

    @Test
    void customProviderWithOpenRouterBaseUrlMatches() {
        assertThat(OpenRouterBilling.isOpenRouterModel(
            "custom", "https://openrouter.ai/api/v1")).isTrue();
    }

    @Test
    void ordinaryCustomProviderDoesNotMatch() {
        assertThat(OpenRouterBilling.isOpenRouterModel(
            "custom", "https://api.openai.com/v1")).isFalse();
        assertThat(OpenRouterBilling.isOpenRouterModel("custom", null)).isFalse();
    }

    @Test
    void laneReadsThePrecomputedExtraFlag() {
        assertThat(OpenRouterBilling.isOpenRouterModel(new ApiOptions(
            "http://localhost", "k", null, 0,
            Map.of(OpenRouterBilling.EXTRA_KEY, Boolean.TRUE)))).isTrue();
        assertThat(OpenRouterBilling.isOpenRouterModel(new ApiOptions(
            "http://localhost", "k", null, 0, Map.of()))).isFalse();
    }

    // ═══════════════════════════════════════════════════════════
    // applyReportedCost
    // ═══════════════════════════════════════════════════════════

    @Test
    void nonByokUsesTheReportedCostDirectly() {
        var out = OpenRouterBilling.applyReportedCost(catalogUsage(0.01), Map.of(
            "cost", 0.004,
            "is_byok", false));

        assertThat(out.cost().total()).isCloseTo(0.004, within(1e-12));
        assertThat(out.cost().totalFromProvider()).isTrue();
        assertThat(out.cost().input()).as("目录分量不变").isEqualTo(0.01);
    }

    @Test
    void byokAddsTheUpstreamTotal() {
        var out = OpenRouterBilling.applyReportedCost(catalogUsage(0.01), Map.of(
            "cost", 0.1,
            "is_byok", true,
            "cost_details", Map.of("upstream_inference_cost", 0.9)));

        assertThat(out.cost().total()).isCloseTo(1.0, within(1e-12));
        assertThat(out.cost().totalFromProvider()).isTrue();
    }

    @Test
    void byokWithExplicitNullUpstreamSumsTheTwoComponents() {
        var details = new LinkedHashMap<String, Object>();
        details.put("upstream_inference_cost", null);
        details.put("upstream_inference_prompt_cost", 0.6);
        details.put("upstream_inference_completions_cost", 0.3);

        var out = OpenRouterBilling.applyReportedCost(catalogUsage(0.01), Map.of(
            "cost", 0.1,
            "is_byok", true,
            "cost_details", details));

        assertThat(out.cost().total()).isCloseTo(1.0, within(1e-12));
        assertThat(out.cost().totalFromProvider()).isTrue();
    }

    @Test
    void absentUpstreamTotalLeavesTheCatalogEstimateAlone() {
        // pi 的 undefined 不满足 `=== null` ⇒ 不走两分量求和分支，整体不动。
        var usage = catalogUsage(0.01);
        var out = OpenRouterBilling.applyReportedCost(usage, Map.of(
            "cost", 0.1,
            "is_byok", true,
            "cost_details", Map.of(
                "upstream_inference_prompt_cost", 0.6,
                "upstream_inference_completions_cost", 0.3)));

        assertThat(out).isSameAs(usage);
    }

    @Test
    void missingCostKeyLeavesTheCatalogEstimateAlone() {
        var usage = catalogUsage(0.01);
        assertThat(OpenRouterBilling.applyReportedCost(usage, Map.of("is_byok", false)))
            .isSameAs(usage);
    }

    @Test
    void byokWithoutCostDetailsLeavesTheCatalogEstimateAlone() {
        var usage = catalogUsage(0.01);
        assertThat(OpenRouterBilling.applyReportedCost(usage, Map.of(
            "cost", 0.1, "is_byok", true)))
            .isSameAs(usage);
    }

    @Test
    void missingIsByokLeavesTheCatalogEstimateAlone() {
        var usage = catalogUsage(0.01);
        assertThat(OpenRouterBilling.applyReportedCost(usage, Map.of("cost", 0.1)))
            .isSameAs(usage);
    }

    @Test
    void invalidCostLeavesTheCatalogEstimateAlone() {
        var usage = catalogUsage(0.01);
        assertThat(OpenRouterBilling.applyReportedCost(usage, Map.of(
            "cost", -1, "is_byok", false)))
            .isSameAs(usage);
    }

    /** 目录估算后的 usage：分量非零、{@code totalFromProvider = false}。 */
    private static Usage catalogUsage(double total) {
        return new Usage(10, 20, 0, 0, null, 0.0, 30,
            new Usage.Cost(0.01, 0.02, 0, 0, total, false));
    }
}
