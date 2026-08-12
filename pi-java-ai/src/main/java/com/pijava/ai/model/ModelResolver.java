package com.pijava.ai.model;

import java.util.Optional;
import java.util.Set;

/**
 * Selects the best model for a given task based on capability matching.
 */
public interface ModelResolver {
    /**
     * Resolve the best model for the given requirements.
     *
     * @param required  minimum capabilities required
     * @param preferred optional preferred model family (e.g. "anthropic")
     * @return the resolved ModelId
     */
    ModelId<?> resolve(Set<ModelCapability> required, Optional<String> preferred);
}
