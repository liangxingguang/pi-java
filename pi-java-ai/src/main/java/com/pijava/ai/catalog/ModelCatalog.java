package com.pijava.ai.catalog;

import java.util.List;
import java.util.Optional;

import com.pijava.ai.model.ModelId;

/**
 * A catalog of known models for a provider or for the whole system.
 *
 * <p>Catalogs can be built-in (shipped with pi-java) or fetched
 * from a remote endpoint at startup.</p>
 */
public interface ModelCatalog {

    /** List all models in this catalog. */
    List<ModelInfo> listModels();

    /** Find a model by its ID. */
    Optional<ModelInfo> find(ModelId<?> id);

    /** Find models that match a search query (fuzzy). */
    List<ModelInfo> search(String query);

    /** An empty catalog (used by FauxProvider). */
    static ModelCatalog empty() {
        return new ModelCatalog() {
            @Override public List<ModelInfo> listModels() { return List.of(); }
            @Override public Optional<ModelInfo> find(ModelId<?> id) { return Optional.empty(); }
            @Override public List<ModelInfo> search(String query) { return List.of(); }
        };
    }
}
