package com.pijava.ai.provider;

/**
 * Provider factory SPI.
 *
 * <p>Third-party JARs implement this interface and register via
 * {@code META-INF/services/com.pijava.ai.provider.ProviderFactory}
 * for automatic discovery through {@link java.util.ServiceLoader}.</p>
 *
 * <p>Phase 1 uses manual registration as the primary channel;
 * ServiceLoader discovery is reserved for Phase 6 extension.</p>
 */
public interface ProviderFactory {

    /** Create a new Provider instance. Must not return {@code null}. */
    Provider create();

    /** The name of the provider this factory creates (used for deduplication). */
    String providerName();
}
