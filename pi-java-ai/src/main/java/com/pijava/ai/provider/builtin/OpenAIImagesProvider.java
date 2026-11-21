package com.pijava.ai.provider.builtin;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ImageApi;
import com.pijava.ai.api.ProviderApi;
import com.pijava.ai.catalog.BuiltinCatalog;
import com.pijava.ai.catalog.ModelCatalog;
import com.pijava.ai.catalog.ModelCompat;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;
import com.pijava.ai.protocol.OpenAIImagesApi;
import com.pijava.ai.provider.ConfigurableProvider;
import com.pijava.ai.provider.Protocol;
import com.pijava.ai.provider.ProviderConfig;
import com.pijava.ai.thinking.ThinkingLevelMap;

/**
 * OpenAI image generation provider for {@code POST /images/generations}.
 *
 * <p>Image-only provider, mirroring {@link OpenRouterImagesProvider}: it uses
 * {@code OPENAI_API_KEY} and the standard OpenAI base URL. The model list is a
 * small static catalog because the vendored pi catalog only carries chat
 * models for OpenAI; new image models are added here explicitly.</p>
 */
public final class OpenAIImagesProvider extends ConfigurableProvider {

    private static final String ID = "openai-images";
    private static final ModelCatalog MODELS = BuiltinCatalog.of(List.of(
        imageModel("gpt-image-1", "GPT Image 1"),
        imageModel("dall-e-3", "DALL-E 3")));

    private static ModelInfo imageModel(String id, String displayName) {
        return new ModelInfo(
            ModelId.of(ID, id), displayName,
            Set.of(ModelCapability.IMAGE_OUTPUT), 0, 0, false,
            PricingInfo.UNKNOWN, ThinkingLevelMap.empty(), Map.of(), Map.of(),
            ModelCompat.NONE, null, null, null, null, Map.of());
    }

    @Override
    protected ProviderConfig config() {
        return new ProviderConfig(
            ID, "OpenAI Images", "https://api.openai.com/v1", List.of("OPENAI_API_KEY"),
            Protocol.OPENAI_COMPLETIONS, Set.of(), MODELS);
    }

    @Override
    public Set<Class<? extends ProviderApi>> supportedApis() {
        return Set.of(ImageApi.class);
    }

    @Override
    protected ImageApi createImageApi(Protocol protocol, ApiOptions options) {
        return new OpenAIImagesApi(options, config().apiKeyEnvVar());
    }
}
