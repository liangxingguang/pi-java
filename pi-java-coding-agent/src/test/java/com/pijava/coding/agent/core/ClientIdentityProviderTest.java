package com.pijava.coding.agent.core;

import java.util.Map;
import java.util.Set;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.AuthKind;
import com.pijava.ai.catalog.ModelCompat;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.identity.AttributionSetting;
import com.pijava.ai.identity.ClientAttribution;
import com.pijava.ai.identity.UserAgentMode;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.thinking.ThinkingLevelMap;
import com.pijava.coding.agent.cli.Version;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/14 B266：pi {@code client-identity.ts:7-14} 的 coding-agent 落点。
 *
 * <p>归因门两层：{@code PI_TELEMETRY} 环境变量优先（truthy 才开），否则读
 * settings 的 {@code enableInstallTelemetry}（缺省开）。设置关闭时必须落到
 * 显式 {@code Off}（三态的「关」），不能是 {@code Unset}——后者的语义是
 * 「沿用下层」，会把 agent 默认的 PI 归因漏出去。</p>
 *
 * <p>环境变量走 {@link ClientIdentityProvider#get(Settings, String)} 第二参注入，
 * 不依赖本进程真实的 {@code PI_TELEMETRY}（pi 的默认参注入同手法）。</p>
 */
class ClientIdentityProviderTest {

    @Test
    void reportsDetailedUserAgentWithTheCodingAgentVersion() {
        var identity = ClientIdentityProvider.get(new Settings(), null);

        assertThat(identity.userAgent()).isEqualTo(UserAgentMode.DETAILED);
        assertThat(identity.version()).isEqualTo(Version.VERSION);
        assertThat(ClientIdentityProvider.userAgent())
            .startsWith("pi-java/" + Version.VERSION + " (")
            .contains("java/");
    }

    @Test
    void attributesPiWhenInstallTelemetryIsEnabled() {
        var settings = new Settings();
        settings.enableInstallTelemetry = true;

        assertThat(ClientIdentityProvider.get(settings, null).attribution())
            .isEqualTo(AttributionSetting.of(ClientAttribution.PI));
    }

    @Test
    void explicitOffWhenInstallTelemetryIsDisabled() {
        var settings = new Settings();
        settings.enableInstallTelemetry = false;

        // Off ≠ Unset：显式否决，覆盖 agent 层的默认 PI 归因。
        var identity = ClientIdentityProvider.get(settings, null);
        assertThat(identity.attribution()).isEqualTo(AttributionSetting.OFF);
        assertThat(identity.attribution()).isNotEqualTo(AttributionSetting.UNSET);
    }

    @Test
    void absentSettingDefaultsToEnabled() {
        var settings = new Settings();
        settings.enableInstallTelemetry = null;

        assertThat(ClientIdentityProvider.get(settings, null).attribution())
            .isEqualTo(AttributionSetting.of(ClientAttribution.PI));
    }

    @Test
    void envFlagOverridesTheSetting() {
        var settings = new Settings();
        settings.enableInstallTelemetry = false;

        // pi: telemetryEnv 在场时只按它裁决（"0" ⇒ 关，"1" ⇒ 开）。
        assertThat(ClientIdentityProvider.get(settings, "1").attribution())
            .isEqualTo(AttributionSetting.of(ClientAttribution.PI));
        assertThat(ClientIdentityProvider.get(settings, "0").attribution())
            .isEqualTo(AttributionSetting.OFF);

        var enabled = new Settings();
        enabled.enableInstallTelemetry = true;
        assertThat(ClientIdentityProvider.get(enabled, "").attribution())
            .isEqualTo(AttributionSetting.OFF);
    }

    @Test
    void nullSettingsStillProducesAnIdentity() {
        var identity = ClientIdentityProvider.get(null, null);

        assertThat(identity.userAgent()).isEqualTo(UserAgentMode.DETAILED);
        assertThat(identity.attribution())
            .isEqualTo(AttributionSetting.of(ClientAttribution.PI));
        assertThat(identity.version()).isEqualTo(Version.VERSION);
    }

    // ── OpenCode session 头（pi getOpenCodeSessionHeaders 的宿主应用点）──────

    @Test
    void injectsSessionHeaderForOpenCodeTarget() {
        var options = options();
        var model = model("https://opencode.ai/zen/v1");

        var adopted = ClientIdentityProvider.withOpenCodeSessionHeaders(
            options, model, "https://default.example", "session-1");

        assertThat(identityHeadersOf(adopted)).containsEntry("x-opencode-session", "session-1");
        assertThat(adopted.identity()).isSameAs(options.identity());
        assertThat(adopted.telemetry()).isSameAs(options.telemetry());
    }

    @Test
    void fallsBackToProviderDefaultBaseUrlWhenModelHasNone() {
        var options = options();
        var model = new ModelInfo(ModelId.of("opencode", "opus"), "Opus", Set.of(),
            128_000, 16_384, false, PricingInfo.UNKNOWN);

        var adopted = ClientIdentityProvider.withOpenCodeSessionHeaders(
            options, model, "https://opencode.ai/zen/v1", "session-1");

        assertThat(identityHeadersOf(adopted)).containsEntry("x-opencode-session", "session-1");
    }

    @Test
    void existingHeadersWinOverTheInjectedSessionHeader() {
        var options = new ApiOptions("https://opencode.ai/zen/v1", "sk",
            java.time.Duration.ofSeconds(1), 0,
            Map.of("headers", Map.of("x-opencode-session", "caller")),
            AuthKind.API_KEY, null, null);

        var adopted = ClientIdentityProvider.withOpenCodeSessionHeaders(
            options, model("https://opencode.ai/zen/v1"), "", "session-1");

        assertThat(identityHeadersOf(adopted)).containsEntry("x-opencode-session", "caller");
    }

    @Test
    void nonOpenCodeTargetIsReturnedUntouched() {
        var options = options();
        var model = new ModelInfo(ModelId.of("custom", "opus"), "Opus", Set.of(),
            128_000, 16_384, false, PricingInfo.UNKNOWN,
            ThinkingLevelMap.empty(), Map.of(), Map.of(),
            ModelCompat.NONE, null, "https://api.openai.com/v1");

        var adopted = ClientIdentityProvider.withOpenCodeSessionHeaders(
            options, model, "", "session-1");

        assertThat(adopted).isSameAs(options);
    }

    @Test
    void absentOrEmptySessionIdInjectsNothing() {
        var options = options();

        assertThat(ClientIdentityProvider.withOpenCodeSessionHeaders(
            options, model("https://opencode.ai/zen/v1"), "", null)).isSameAs(options);
        assertThat(ClientIdentityProvider.withOpenCodeSessionHeaders(
            options, model("https://opencode.ai/zen/v1"), "", "")).isSameAs(options);
    }

    private static ApiOptions options() {
        return new ApiOptions("https://opencode.ai/zen/v1", "sk",
            java.time.Duration.ofSeconds(1), 0, Map.of(), AuthKind.API_KEY, null, null);
    }

    private static ModelInfo model(String baseUrl) {
        return new ModelInfo(ModelId.of("opencode", "opus"), "Opus", Set.of(),
            128_000, 16_384, false, PricingInfo.UNKNOWN,
            ThinkingLevelMap.empty(), Map.of(), Map.of(),
            ModelCompat.NONE, null, baseUrl);
    }

    private static Map<String, Object> identityHeadersOf(ApiOptions options) {
        Object raw = options.extra().get("headers");
        var out = new java.util.LinkedHashMap<String, Object>();
        if (raw instanceof Map<?, ?> map) {
            map.forEach((key, value) -> out.put(String.valueOf(key), value));
        }
        return out;
    }
}
