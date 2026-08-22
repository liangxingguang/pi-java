package com.pijava.ai.auth;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6: OAuthProviders 注册表 — 全量 provider 端点/client-id 对齐 pi {@code auth/oauth/}。
 */
class OAuthProvidersTest {

    @Test
    void allPiProvidersAreRegistered() {
        assertThat(OAuthProviders.names()).containsExactlyInAnyOrder(
            "openrouter", "anthropic", "xai", "kimi", "github-copilot", "openai-codex");
    }

    @Test
    void openRouterIsPkce() {
        assertThat(OAuthProviders.get("openrouter")).isPresent();
        assertThat(OAuthProviders.get("openrouter").get())
            .isInstanceOf(OAuthProvider.Pkce.class);
    }

    @Test
    void anthropicIsPkceWithPiEndpoints() {
        var spec = (OAuthProvider.Pkce) OAuthProviders.get("anthropic").orElseThrow();
        var config = spec.config();
        assertThat(config.authorizeTemplate()).startsWith("https://claude.ai/oauth/authorize");
        assertThat(config.tokenUrl()).isEqualTo("https://platform.claude.com/v1/oauth/token");
        assertThat(config.clientId()).isEqualTo("9d1c250a-e61b-44d9-88ed-5944d1962f5e");
        assertThat(config.scope()).contains("user:inference");
    }

    @Test
    void deviceProvidersCarryPiEndpoints() {
        var xai = device("xai");
        assertThat(xai.deviceCodeUrl()).isEqualTo("https://auth.x.ai/oauth2/device/code");
        assertThat(xai.tokenUrl()).isEqualTo("https://auth.x.ai/oauth2/token");
        assertThat(xai.clientId()).isEqualTo("b1a00492-073a-47ea-816f-4c329264a828");
        assertThat(xai.scope()).contains("grok-cli:access");

        var kimi = device("kimi");
        assertThat(kimi.deviceCodeUrl()).isEqualTo("https://auth.kimi.com/api/oauth/device_authorization");
        assertThat(kimi.tokenUrl()).isEqualTo("https://auth.kimi.com/api/oauth/token");
        assertThat(kimi.clientId()).isEqualTo("17e5f671-d194-4dfb-9706-5516cb48c098");

        var copilot = device("github-copilot");
        assertThat(copilot.deviceCodeUrl()).isEqualTo("https://github.com/login/device/code");
        assertThat(copilot.tokenUrl()).isEqualTo("https://github.com/login/oauth/access_token");
        assertThat(copilot.clientId()).isEqualTo("Iv1.b507a08c87ecfe98");
        assertThat(copilot.scope()).isEqualTo("read:user");
    }

    @Test
    void openAiCodexIsTwoStageDeviceFlow() {
        var config = device("openai-codex");
        assertThat(config.deviceStyle()).isEqualTo(DeviceCodeConfig.DeviceAuthStyle.OPENAI_CODEX);
        assertThat(config.deviceCodeUrl())
            .isEqualTo("https://auth.openai.com/api/accounts/deviceauth/usercode");
        assertThat(config.tokenUrl())
            .isEqualTo("https://auth.openai.com/api/accounts/deviceauth/token");
        assertThat(config.exchangeUrl()).isEqualTo("https://auth.openai.com/oauth/token");
        assertThat(config.exchangeRedirectUri())
            .isEqualTo("https://auth.openai.com/deviceauth/callback");
        assertThat(config.verificationUri()).isEqualTo("https://auth.openai.com/codex/device");
    }

    @Test
    void radiusFactoryBuildsGatewayConfig() {
        var spec = (OAuthProvider.Device) OAuthProviders.radius("radius", "https://gateway.example.com/");
        var config = spec.config();
        assertThat(config.deviceCodeUrl()).isEqualTo("https://gateway.example.com/v1/oauth/device");
        assertThat(config.tokenUrl()).isEqualTo("https://gateway.example.com/v1/oauth/token");
        assertThat(config.clientId()).isEqualTo("pi-gateway");
        assertThat(config.scope()).isEqualTo("gateway offline_access");
    }

    private static DeviceCodeConfig device(String name) {
        var spec = OAuthProviders.get(name).orElseThrow();
        assertThat(spec).isInstanceOf(OAuthProvider.Device.class);
        return ((OAuthProvider.Device) spec).config();
    }
}
