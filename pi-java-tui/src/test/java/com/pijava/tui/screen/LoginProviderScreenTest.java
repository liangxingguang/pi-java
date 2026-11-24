package com.pijava.tui.screen;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.pijava.coding.agent.core.LoginAuthType;
import com.pijava.coding.agent.core.LoginProviderOption;

/**
 * L7 W8（漏项1后半，pi oauth-selector.ts:190）：伙伴 provider 行带 {@code ✦}。
 */
class LoginProviderScreenTest {

    private static LoginProviderOption option(String id, String name) {
        return new LoginProviderOption(id, name, LoginAuthType.API_KEY, null, false);
    }

    @Test
    void preferredPartnersCarryTheMarker() {
        assertThat(LoginProviderScreen.label(option("radius", "Radius")))
            .isEqualTo("Radius ✦ (API key)");
        assertThat(LoginProviderScreen.label(option("openrouter", "OpenRouter")))
            .isEqualTo("OpenRouter ✦ (API key)");
    }

    @Test
    void otherProvidersCarryNoMarker() {
        assertThat(LoginProviderScreen.label(option("anthropic", "Anthropic")))
            .isEqualTo("Anthropic (API key)");
    }
}
