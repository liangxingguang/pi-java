package com.pijava.ai.provider;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

import com.pijava.ai.provider.builtin.ProviderCatalog;

/**
 * Central registry for LLM {@link Provider} instances.
 *
 * <p>Providers can be registered manually, loaded from
 * {@link ProviderCatalog}, or discovered through {@link ServiceLoader}.
 * The registry is thread-safe.</p>
 */
public final class ProviderRegistry {

    private static final ProviderRegistry INSTANCE = new ProviderRegistry();

    private final Map<String, Provider> providers = new ConcurrentHashMap<>();

    /** Create a new empty registry (for non-singleton use). */
    private ProviderRegistry() {}

    /** Create a new empty registry instance. */
    public static ProviderRegistry create() {
        return new ProviderRegistry();
    }

    /** The global singleton registry. */
    public static ProviderRegistry global() {
        return INSTANCE;
    }

    /** Register a provider manually. Replaces any existing registration. */
    public void register(Provider provider) {
        providers.put(provider.name(), provider);
    }

    /** Look up a provider by name. */
    public Optional<Provider> get(String name) {
        return Optional.ofNullable(providers.get(name));
    }

    /** List all registered providers. */
    public List<Provider> listAll() {
        return List.copyOf(providers.values());
    }

    /**
     * List registered providers that support {@code protocol}.
     *
     * @param protocol protocol family to filter by
     * @return matching providers in unspecified order
     */
    public List<Provider> listByProtocol(Protocol protocol) {
        Objects.requireNonNull(protocol, "protocol");
        return providers.values().stream()
            .filter(p -> p.supportedProtocols().contains(protocol))
            .toList();
    }

    /**
     * Register every built-in provider from {@link ProviderCatalog}.
     *
     * @return number of providers registered
     */
    public int loadBuiltinProviders() {
        int count = 0;
        for (var provider : ProviderCatalog.all()) {
            register(provider);
            count++;
        }
        return count;
    }

    /** Discover and register providers via ServiceLoader. */
    public void discoverFromServiceLoader() {
        var loader = ServiceLoader.load(ProviderFactory.class);
        for (var factory : loader) {
            var provider = factory.create();
            providers.putIfAbsent(provider.name(), provider);
        }
    }

    /** Remove a provider by name. */
    public void remove(String name) {
        providers.remove(name);
    }

    /** Remove all providers. */
    public void clear() {
        providers.clear();
    }
}
