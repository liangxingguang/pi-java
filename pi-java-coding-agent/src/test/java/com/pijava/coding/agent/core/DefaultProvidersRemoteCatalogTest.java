package com.pijava.coding.agent.core;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 原 docs/70 §4.6／§7.1-12：装配层给内置 provider 包上远程目录（图片 provider 除外），
 * 且尚未刷新时 {@code getModels()} 与静态目录一致。
 */
class DefaultProvidersRemoteCatalogTest {

    @Test
    void wrapsBuiltinChatProviders() {
        var registry = DefaultProviders.defaultProviders("http://127.0.0.1:1");

        assertThat(registry.get("openai").orElseThrow())
            .isInstanceOf(RemoteCatalogProvider.class);
        assertThat(registry.get("anthropic").orElseThrow())
            .isInstanceOf(RemoteCatalogProvider.class);
    }

    @Test
    void wrappedProviderDelegatesIdentityAndStaticCatalog() {
        var registry = DefaultProviders.defaultProviders("http://127.0.0.1:1");
        var openai = registry.get("openai").orElseThrow();

        assertThat(openai.name()).isEqualTo("openai");
        assertThat(openai.displayName()).isNotEmpty();
        assertThat(openai.supportedProtocols()).isNotEmpty();
        // 没有刷新过 ⇒ overlay 为空 ⇒ getModels 就是静态目录
        assertThat(openai.getModels())
            .containsExactlyElementsOf(openai.builtinModels().listModels());
    }

    @Test
    void doesNotWrapTheImagesProvider() {
        var registry = DefaultProviders.defaultProviders("http://127.0.0.1:1");

        assertThat(registry.get("openrouter-images").orElseThrow())
            .isNotInstanceOf(RemoteCatalogProvider.class);
    }
}
