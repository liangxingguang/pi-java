package com.pijava.ai.provider;

import java.util.Set;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ProviderApi;
import com.pijava.ai.catalog.ModelCatalog;

/**
 * Service Provider Interface (SPI) for LLM providers.
 *
 * <p>Each provider (Anthropic, OpenAI, Google, etc.) implements this
 * interface. Providers are discovered via {@link java.util.ServiceLoader}
 * and registered in the global provider registry.</p>
 *
 * <p>The {@link ProviderApi} sealed hierarchy constrains which API types
 * a provider can expose. Phase 1 only defines {@code ChatApi}; additional
 * modalities arrive in Phase 6.</p>
 */
public interface Provider {

    /** Machine-readable name, e.g. "anthropic", "openai". */
    String name();

    /** Human-readable display name, e.g. "Anthropic". */
    String displayName();

    /** The set of API types this provider can create. */
    Set<Class<? extends ProviderApi>> supportedApis();

    /** Create an API instance of the given type. */
    <T extends ProviderApi> T createApi(Class<T> apiType, ApiOptions options);

    /** The built-in model catalog for this provider. */
    ModelCatalog builtinModels();
}
