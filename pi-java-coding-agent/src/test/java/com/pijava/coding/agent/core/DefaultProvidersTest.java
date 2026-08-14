package com.pijava.coding.agent.core;

import com.pijava.coding.agent.cli.ArgsParser;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 review fix: settings {@code defaultProvider} now drives the provider
 * when {@code --provider} is absent.
 */
class DefaultProvidersTest {

    @Test
    void resolveProviderNamePrefersCliThenSettingsThenDefault() {
        var cli = ArgsParser.parse(new String[] {"--provider", "openai"});
        assertThat(DefaultProviders.resolveProviderName(cli, "deepseek"))
            .isEqualTo("openai");

        var noCli = ArgsParser.parse(new String[] {});
        assertThat(DefaultProviders.resolveProviderName(noCli, "deepseek"))
            .isEqualTo("deepseek");

        assertThat(DefaultProviders.resolveProviderName(noCli, null))
            .isEqualTo("google");
    }
}
