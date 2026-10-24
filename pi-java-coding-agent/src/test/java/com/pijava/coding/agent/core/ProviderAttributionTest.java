package com.pijava.coding.agent.core;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.catalog.ModelCompat;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.thinking.ThinkingLevelMap;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 20（B253，T6）：pi {@code provider-attribution.ts}（:1-97）与
 * {@code telemetry.ts}（:3-13）逐字对拍——四家判定、遥测门、合并序、空集不造键。
 */
class ProviderAttributionTest {

    private static ModelInfo model(String provider, String baseUrl) {
        return new ModelInfo(
            ModelId.of(provider, "m-1"), "M One", Set.of(ModelCapability.TEXT),
            1, 1, false, PricingInfo.UNKNOWN,
            ThinkingLevelMap.empty(), Map.of(), Map.of(),
            ModelCompat.NONE, null, baseUrl);
    }

    private static ApiOptions options(Map<String, Object> extra) {
        return new ApiOptions("https://req.example/v1", "k", Duration.ofSeconds(30),
            2, extra, com.pijava.ai.api.AuthKind.API_KEY);
    }

    @SuppressWarnings("unchecked") // 测试读回自己写入的 extra["headers"]
    private static Map<String, String> headers(ApiOptions options) {
        return (Map<String, String>) options.extra().get("headers");
    }

    // ── 遥测门（pi telemetry.ts:3-13 逐字）──

    @Test
    void telemetryGateEnvTruthyTable() {
        // pi isTruthyEnvFlag："1"/"true"/"yes"（后两个大小写不敏感）。
        assertThat(InstallTelemetry.isEnabled(Boolean.FALSE, "1")).isTrue();
        assertThat(InstallTelemetry.isEnabled(Boolean.FALSE, "TRUE")).isTrue();
        assertThat(InstallTelemetry.isEnabled(Boolean.FALSE, "yes")).isTrue();
        assertThat(InstallTelemetry.isEnabled(Boolean.TRUE, "0")).isFalse();
        assertThat(InstallTelemetry.isEnabled(Boolean.TRUE, "no")).isFalse();
        assertThat(InstallTelemetry.isEnabled(Boolean.TRUE, "")).isFalse();
        // env 缺席 ⇒ settings（?? true）。
        assertThat(InstallTelemetry.isEnabled(null, null)).isTrue();
        assertThat(InstallTelemetry.isEnabled(Boolean.TRUE, null)).isTrue();
        assertThat(InstallTelemetry.isEnabled(Boolean.FALSE, null)).isFalse();
    }

    // ── 四家判定（pi :19-34 逐字）──

    @Test
    void openrouterByProviderNameGetsThreeHeaders() {
        var result = ProviderAttribution.apply(options(Map.of()),
            model("openrouter", null), null, true);
        assertThat(headers(result)).containsExactlyInAnyOrderEntriesOf(Map.of(
            "HTTP-Referer", "https://pi.dev",
            "X-OpenRouter-Title", "pi",
            "X-OpenRouter-Categories", "cli-agent"));
    }

    @Test
    void openrouterByBaseUrlContains() {
        // pi :20 逐字——contains（非 host 等值）：relay 子路径也中。
        var result = ProviderAttribution.apply(options(Map.of()),
            model("relay", "https://openrouter.ai/api/v1"), null, true);
        assertThat(headers(result)).containsEntry("X-OpenRouter-Title", "pi");
    }

    @Test
    void nvidiaByHostEquality() {
        var result = ProviderAttribution.apply(options(Map.of()),
            model("other", "https://integrate.api.nvidia.com/v1"), null, true);
        assertThat(headers(result)).containsExactly(
            Map.entry("X-BILLING-INVOKE-ORIGIN", "Pi"));
    }

    @Test
    void cloudflareByGatewayHost() {
        var result = ProviderAttribution.apply(options(Map.of()),
            model("other", "https://gateway.ai.cloudflare.com/v1/x"), null, true);
        assertThat(headers(result)).containsExactly(
            Map.entry("User-Agent", "pi-coding-agent"));
    }

    @Test
    void unrelatedProviderGetsNoAttributionAndNoHeadersKey() {
        var base = options(Map.of());
        var result = ProviderAttribution.apply(base, model("anthropic", null), null, true);
        // pi：空集 ⇒ undefined——不造 extra["headers"] 键。
        assertThat(result).isSameAs(base);
        assertThat(result.extra()).doesNotContainKey("headers");
    }

    @Test
    void attributionSuppressedWhenTelemetryDisabled() {
        var result = ProviderAttribution.apply(options(Map.of()),
            model("openrouter", null), null, false);
        assertThat(result.extra()).doesNotContainKey("headers");
    }

    // ── opencode session 头（pi :67-77——不过遥测门）──

    @Test
    void opencodeSessionHeadersBypassTelemetryGate() {
        var extra = new LinkedHashMap<String, Object>();
        extra.put("sessionId", "sess-1");
        var result = ProviderAttribution.apply(options(extra),
            model("opencode", null), null, false);
        assertThat(headers(result)).containsExactly(
            Map.entry("x-opencode-session", "sess-1"),
            Map.entry("x-opencode-client", "pi"));
    }

    @Test
    void opencodeNeedsSessionId() {
        var result = ProviderAttribution.apply(options(Map.of()),
            model("opencode-go", null), null, true);
        assertThat(result.extra()).doesNotContainKey("headers");
    }

    // ── 合并序（pi :85-94：请求头最后赢）──

    @Test
    void existingHeadersWinOverAttribution() {
        var extra = new LinkedHashMap<String, Object>();
        extra.put("headers", Map.of(
            "X-OpenRouter-Title", "user-override", "X-Custom", "keep"));
        var result = ProviderAttribution.apply(options(extra),
            model("openrouter", null), null, true);
        assertThat(headers(result))
            .containsEntry("X-OpenRouter-Title", "user-override")
            .containsEntry("X-Custom", "keep")
            .containsEntry("HTTP-Referer", "https://pi.dev");
    }

    @Test
    void matchesHostFailureIsFalse() {
        // pi :13-16 逐字——非法 URL ⇒ false（不炸）。
        var result = ProviderAttribution.apply(options(Map.of()),
            model("other", ":::not-a-url"), null, true);
        assertThat(result.extra()).doesNotContainKey("headers");
    }

    @Test
    void providerDefaultBaseUrlIsFallbackForModelWithoutBaseUrl() {
        // 裁决⑥：modelInfo.baseUrl 缺席 ⇒ provider 静态默认（pi model.baseUrl
        // 目录值语义）——本例 catalog 缺 baseUrl、默认指向 nvidia host。
        var result = ProviderAttribution.apply(options(Map.of()),
            model("other", null), "https://integrate.api.nvidia.com/v1", true);
        assertThat(headers(result)).containsEntry("X-BILLING-INVOKE-ORIGIN", "Pi");
    }
}
