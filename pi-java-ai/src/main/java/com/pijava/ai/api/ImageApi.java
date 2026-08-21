package com.pijava.ai.api;

/**
 * Image generation capability — aligned with pi {@code ImagesFunction}.
 *
 * <p>Takes text/image input content and returns generated image output
 * (plus optional text), mirroring pi's {@code AssistantImages} shape.</p>
 */
public non-sealed interface ImageApi extends ProviderApi {

    /**
     * Generate images from the given prompt input.
     *
     * @param request the image request (model + input content blocks)
     * @param options API call options (api key, base URL, timeouts)
     * @return the generated images and optional text
     */
    ImageResult generate(ImageRequest request, ApiOptions options);
}
