package com.pijava.ai.provider.builtin;

import java.util.Set;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ImageApi;
import com.pijava.ai.api.ProviderApi;
import com.pijava.ai.catalog.BuiltinCatalog;
import com.pijava.ai.protocol.OpenRouterImagesApi;
import com.pijava.ai.provider.ConfigurableProvider;
import com.pijava.ai.provider.Protocol;
import com.pijava.ai.provider.ProviderConfig;

/**
 * OpenRouter image generation provider（P6-28）—— 镜像 pi {@code openrouter-images.ts}。
 *
 * <p>图片专用 provider：chat completions + {@code modalities} 返回图片，baseUrl
 * {@code https://openrouter.ai/api/v1}，auth {@code OPENROUTER_API_KEY}。与
 * chat provider 分离（如同 pi 的独立 ImagesProvider）。</p>
 */
public final class OpenRouterImagesProvider extends ConfigurableProvider {

    @Override
    protected ProviderConfig config() {
        return new ProviderConfig(
            "openrouter-images", "OpenRouter Images",
            "https://openrouter.ai/api/v1", "OPENROUTER_API_KEY",
            Protocol.OPENAI_COMPLETIONS, Set.of(),
            BuiltinCatalog.openRouterImageModels());
    }

    @Override
    public Set<Class<? extends ProviderApi>> supportedApis() {
        return Set.of(ImageApi.class);
    }

    @Override
    protected ImageApi createImageApi(Protocol protocol, ApiOptions options) {
        return new OpenRouterImagesApi(options, config().apiKeyEnvVar());
    }
}
