package com.pijava.ai.model;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.pijava.ai.catalog.ModelCatalog;

/**
 * Default model resolver: selects the first model that satisfies all required
 * capabilities, preferring the given provider family if specified.
 */
public final class DefaultModelResolver implements ModelResolver {

    private final ModelCatalog catalog;

    public DefaultModelResolver(ModelCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public ModelId<?> resolve(Set<ModelCapability> required, Optional<String> preferred) {
        List<ModelId<?>> candidates = catalog.listModels().stream()
            .filter(info -> info.capabilities().containsAll(required))
            .<ModelId<?>>map(info -> info.id())
            .toList();

        if (candidates.isEmpty()) {
            throw new IllegalStateException(
                "No model found with required capabilities: " + required);
        }

        // Prefer the specified provider
        if (preferred.isPresent()) {
            for (var c : candidates) {
                if (c.provider().equalsIgnoreCase(preferred.get())) {
                    return c;
                }
            }
        }

        return candidates.getFirst();
    }

    /**
     * Resolve a CLI model pattern to a concrete model.
     *
     * <p>Phase 3: supports {@code "provider/model"}, bare {@code "model"}, and
     * an optional {@code ":thinking"} suffix (e.g. {@code "claude-sonnet:high"})
     * which is parsed off and ignored here — the thinking level is applied by
     * the CLI layer via {@code --thinking}. A bare provider name resolves to
     * the provider's first listed model.</p>
     *
     * @param pattern model pattern or ID (may be null → default provider)
     * @return the resolved model ID
     * @throws IllegalStateException if the pattern cannot be resolved
     */
    public ModelId<?> resolve(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return resolve(Set.of(ModelCapability.TEXT), Optional.of("google"));
        }
        var trimmed = pattern.trim();
        var colon = trimmed.lastIndexOf(':');
        var base = colon > 0 ? trimmed.substring(0, colon) : trimmed;

        String provider = null;
        String name = base;
        var slash = base.indexOf('/');
        if (slash > 0) {
            provider = base.substring(0, slash);
            name = base.substring(slash + 1);
        }
        final String providerRef = provider;
        final String nameRef = name;

        if (nameRef.isEmpty()) {
            var byProvider = catalog.listModels().stream()
                .filter(m -> providerRef.equalsIgnoreCase(m.id().provider()))
                .toList();
            if (byProvider.isEmpty()) {
                throw new IllegalStateException("No models found for provider: " + providerRef);
            }
            return byProvider.getFirst().id();
        }

        var matches = catalog.listModels().stream()
            .filter(m -> m.id().modelName().equalsIgnoreCase(nameRef))
            .filter(m -> providerRef == null
                || m.id().provider().equalsIgnoreCase(providerRef))
            .toList();
        if (matches.isEmpty() && providerRef == null) {
            // Bare provider name (e.g. "google") — resolve to its first model.
            var byProvider = catalog.listModels().stream()
                .filter(m -> m.id().provider().equalsIgnoreCase(nameRef))
                .toList();
            if (!byProvider.isEmpty()) {
                return byProvider.getFirst().id();
            }
        }
        if (matches.isEmpty()) {
            throw new IllegalStateException("Unknown model pattern: " + pattern);
        }
        return matches.getFirst().id();
    }
}
