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
}
