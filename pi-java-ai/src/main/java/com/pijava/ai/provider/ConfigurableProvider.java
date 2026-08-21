package com.pijava.ai.provider;

import java.util.Locale;
import java.util.Set;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ChatApi;
import com.pijava.ai.api.EmbeddingApi;
import com.pijava.ai.api.ImageApi;
import com.pijava.ai.api.ProviderApi;
import com.pijava.ai.catalog.ModelCatalog;

/**
 * Config-driven Provider base class — all new Providers reuse this.
 *
 * <p>Subclasses only need to provide a {@link #config()} and implement
 * {@link #createChatApi(Protocol, ApiOptions)}. The common logic for
 * protocol resolution, option normalization, and plumbing is handled here.</p>
 */
public abstract class ConfigurableProvider implements Provider {

    /** Static configuration for this provider. */
    protected abstract ProviderConfig config();

    /**
     * Expose the static config for catalog and registry inspection.
     *
     * @return the provider configuration
     */
    public final ProviderConfig providerConfig() {
        return config();
    }

    @Override
    public final String name() {
        return config().name();
    }

    @Override
    public final String displayName() {
        return config().displayName();
    }

    @Override
    public Set<Class<? extends ProviderApi>> supportedApis() {
        return Set.of(ChatApi.class);
    }

    @Override
    public final Set<Protocol> supportedProtocols() {
        return config().supportedProtocols();
    }

    @Override
    public final ModelCatalog builtinModels() {
        return config().builtinModels();
    }

    /**
     * Unified ApiOptions normalization + protocol routing.
     *
     * <p>Note: createApi is not final — Providers that need extra header/body
     * rewriting can override {@link #createChatApi}.</p>
     */
    @Override
    public <T extends ProviderApi> T createApi(Class<T> apiType, ApiOptions options) {
        var protocol = resolveProtocol(options);
        var opts = effectiveOptions(options);
        if (apiType.equals(ChatApi.class)) {
            return apiType.cast(createChatApi(protocol, opts));
        }
        if (apiType.equals(ImageApi.class)) {
            return apiType.cast(createImageApi(protocol, opts));
        }
        if (apiType.equals(EmbeddingApi.class)) {
            return apiType.cast(createEmbeddingApi(protocol, opts));
        }
        throw new IllegalArgumentException("Unsupported API type: " + apiType);
    }

    /** Read protocol from ApiOptions.extra "protocol" key, fall back to default. */
    protected final Protocol resolveProtocol(ApiOptions options) {
        var raw = options.extra().get("protocol");
        if (raw == null) {
            return config().defaultProtocol();
        }
        var protocol = Protocol.valueOf(
            raw.toString().toUpperCase(Locale.ROOT).replace('-', '_'));
        if (!config().supportedProtocols().contains(protocol)) {
            throw new IllegalArgumentException(
                "Provider " + name() + " does not support protocol " + protocol);
        }
        return protocol;
    }

    /** Fall back to defaultBaseUrl when options.baseUrl is empty. */
    protected final ApiOptions effectiveOptions(ApiOptions options) {
        if (options.baseUrl() != null && !options.baseUrl().isBlank()) {
            return options;
        }
        return new ApiOptions(
            config().defaultBaseUrl(), options.apiKey(),
            options.timeout(), options.maxRetries(), options.extra());
    }

    /** Subclasses construct the protocol adapter here; image-only providers leave it
     *  unimplemented (default throws). */
    protected ChatApi createChatApi(Protocol protocol, ApiOptions options) {
        throw new UnsupportedOperationException(
            "Provider " + name() + " does not support ChatApi");
    }

    /** Subclasses override to support image generation; base providers do not. */
    protected ImageApi createImageApi(Protocol protocol, ApiOptions options) {
        throw new UnsupportedOperationException(
            "Provider " + name() + " does not support ImageApi");
    }

    /** Subclasses override to support embeddings; base providers do not. */
    protected EmbeddingApi createEmbeddingApi(Protocol protocol, ApiOptions options) {
        throw new UnsupportedOperationException(
            "Provider " + name() + " does not support EmbeddingApi");
    }
}
