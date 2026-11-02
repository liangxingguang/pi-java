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
    public Optional<ModelsStoreEntry> read(String providerId, ModelsStoreOperationOptions options) {
        throwIfAborted(options);
        return Optional.ofNullable(entries.get(providerId));
    }

    @Override
    public void write(String providerId, ModelsStoreEntry entry,
                      ModelsStoreOperationOptions options) {
        throwIfAborted(options);
        entries.put(providerId, entry);
    }

    @Override
    public void delete(String providerId, ModelsStoreOperationOptions options) {
        throwIfAborted(options);
        entries.remove(providerId);
    }

    private static void throwIfAborted(ModelsStoreOperationOptions options) {
        if (options != null) {
            options.throwIfAborted();
        }
    }
}
