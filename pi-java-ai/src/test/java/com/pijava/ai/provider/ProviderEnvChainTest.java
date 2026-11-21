package com.pijava.ai.provider;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ChatApi;
import com.pijava.ai.catalog.ModelCatalog;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/11 A2：{@code ConfigurableProvider.effectiveOptions} 在 provider 归一化点
 * 按链注入 {@code apiKey} —— 协议构造器仍只收单 {@code envVar}，链命中后
 * 构造器读到的是 options 上的值。
 */
class ProviderEnvChainTest {

    @Test
    void fallbackEnvKeyReachesTheLaneWhenPrimaryIsAbsent() {
        var provider = new ChainProvider(List.of("PRIMARY_KEY", "FALLBACK_KEY"));

        provider.createApi(ChatApi.class, options("", Map.of("FALLBACK_KEY", "fb-key")));

        assertThat(provider.seen.apiKey()).isEqualTo("fb-key");
    }

    @Test
    void theFirstConfiguredEnvKeyInTheChainWins() {
        var provider = new ChainProvider(List.of("PRIMARY_KEY", "FALLBACK_KEY"));

        provider.createApi(ChatApi.class,
            options("", Map.of("PRIMARY_KEY", "primary-key", "FALLBACK_KEY", "fb-key")));

        assertThat(provider.seen.apiKey()).isEqualTo("primary-key");
    }

    @Test
    void explicitApiKeyIsNeverOverriddenByTheChain() {
        var provider = new ChainProvider(List.of("PRIMARY_KEY"));

        provider.createApi(ChatApi.class,
            options("explicit-key", Map.of("PRIMARY_KEY", "env-key")));

        assertThat(provider.seen.apiKey()).isEqualTo("explicit-key");
    }

    @Test
    void anEmptyChainLeavesTheKeyUntouched() {
        var provider = new ChainProvider(List.of());

        provider.createApi(ChatApi.class, options("", Map.of("ANY_KEY", "env-key")));

        assertThat(provider.seen.apiKey()).isEmpty();
    }

    private static ApiOptions options(String apiKey, Map<String, String> env) {
        return new ApiOptions("", apiKey, Duration.ofSeconds(1), 0, Map.of(),
            com.pijava.ai.api.AuthKind.API_KEY, null, null, env);
    }

    /** Captures the normalized options that reach the lane. */
    private static final class ChainProvider extends ConfigurableProvider {

        private final List<String> chain;

        private ApiOptions seen;

        ChainProvider(List<String> chain) {
            this.chain = chain;
        }

        @Override
        protected ProviderConfig config() {
            return new ProviderConfig(
                "chain", "Chain", "https://default", chain,
                Protocol.OPENAI_COMPLETIONS, Set.of(Protocol.OPENAI_COMPLETIONS),
                ModelCatalog.empty());
        }

        @Override
        protected ChatApi createChatApi(Protocol protocol, ApiOptions options) {
            seen = options;
            return null;
        }
    }
}
