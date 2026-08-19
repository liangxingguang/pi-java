package com.pijava.ai.catalog;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link ModelsStore} 的内存实现（测试用，pi 亦有）。
 */
public final class InMemoryModelsStore implements ModelsStore {

    private final Map<String, ModelsStoreEntry> entries = new ConcurrentHashMap<>();

    @Override
    public Optional<ModelsStoreEntry> read(String providerId) {
        return Optional.ofNullable(entries.get(providerId));
    }

    @Override
    public void write(String providerId, ModelsStoreEntry entry) {
        entries.put(providerId, entry);
    }

    @Override
    public void delete(String providerId) {
        entries.remove(providerId);
    }
}
