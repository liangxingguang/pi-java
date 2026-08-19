package com.pijava.coding.agent.core;

import com.pijava.ai.provider.Provider;
import com.pijava.coding.agent.cli.ArgsParser;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 review fix: settings {@code defaultProvider} now drives the provider
 * when {@code --provider} is absent.
 *
 * <p>Phase 6: {@code defaultProviders()} loads 16 built-ins and runs
 * ServiceLoader discovery.</p>
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

    @Test
    void defaultProvidersIncludesSixteenBuiltins() {
        var names = DefaultProviders.defaultProviders().listAll().stream()
            .map(Provider::name)
            .toList();
        assertThat(names).contains(
            "anthropic", "openai", "google", "deepseek", "mistral",
            "moonshotai-cn", "moonshotai", "zai-coding-cn", "zai",
            "qwen-token-plan-cn", "xiaomi", "xiaomi-token-plan-cn",
            "minimax-cn", "minimax", "ant-ling", "ollama");
        assertThat(names).hasSizeGreaterThanOrEqualTo(16);
    }
}
