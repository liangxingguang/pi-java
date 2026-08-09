package com.pijava.ai.provider;

import java.util.Set;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.catalog.ModelCatalog;

/**
 * Service Provider Interface (SPI) for LLM providers.
 *
 * <p>Each provider (Anthropic, OpenAI, Google, etc.) implements this
 * interface. Providers are discovered via {@link java.util.ServiceLoader}
 * and registered in the global provider registry.</p>
 *
 * <p>Type parameter {@code A} is the set of API interfaces the provider
 * supports (e.g. {@code ChatApi.class}).</p>
 */
public interface Provider {

    /** Machine-readable name, e.g. "anthropic", "openai". */
    String name();

    /** Human-readable display name, e.g. "Anthropic". */
    String displayName();

    /** The set of API types this provider can create. */
    Set<Class<?>> supportedApis();

    /** Create an API instance of the given type. */
    <T> T createApi(Class<T> apiType, ApiOptions options);

    /** The built-in model catalog for this provider. */
    ModelCatalog builtinModels();
}
