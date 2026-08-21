package com.pijava.ai.provider.builtin;

import java.util.ArrayList;
import java.util.List;

import com.pijava.ai.catalog.BuiltinCatalog;
import com.pijava.ai.catalog.ModelCatalog;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.provider.AnthropicProvider;
import com.pijava.ai.provider.DeepSeekProvider;
import com.pijava.ai.provider.GoogleProvider;
import com.pijava.ai.provider.MistralProvider;
import com.pijava.ai.provider.OpenAIProvider;
import com.pijava.ai.provider.Protocol;
import com.pijava.ai.provider.Provider;

/**
 * Built-in Provider catalog — the 5 Phase 1 providers plus the 11
 * Phase 6 China-focused additions (16 total).
 */
public final class ProviderCatalog {

    private ProviderCatalog() {}

    /** All built-in providers in stable registration order. */
    public static List<Provider> all() {
        var providers = new ArrayList<Provider>();
        providers.add(new AnthropicProvider());
        providers.add(new OpenAIProvider());
        providers.add(new GoogleProvider());
        providers.add(new DeepSeekProvider());
        providers.add(new MistralProvider());
        providers.add(new MoonshotAiCnProvider());
        providers.add(new MoonshotAiProvider());
        providers.add(new ZaiCodingCnProvider());
        providers.add(new ZaiProvider());
        providers.add(new QwenTokenPlanCnProvider());
        providers.add(new XiaomiProvider());
        providers.add(new XiaomiTokenPlanCnProvider());
        providers.add(new MiniMaxCnProvider());
        providers.add(new MiniMaxProvider());
        providers.add(new AntLingProvider());
        providers.add(new OllamaProvider());
        providers.add(new OpenRouterImagesProvider());
        return List.copyOf(providers);
    }

    /** Providers that declare support for {@code protocol}. */
    public static List<Provider> byProtocol(Protocol protocol) {
        return all().stream()
            .filter(p -> p.supportedProtocols().contains(protocol))
            .toList();
    }

    /** Aggregate built-in model catalog across every provider. */
    public static ModelCatalog allModels() {
        var models = new ArrayList<ModelInfo>();
        for (var provider : all()) {
            models.addAll(provider.builtinModels().listModels());
        }
        return BuiltinCatalog.of(models);
    }
}
