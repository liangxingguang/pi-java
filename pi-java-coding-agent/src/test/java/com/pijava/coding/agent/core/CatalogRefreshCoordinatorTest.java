package com.pijava.coding.agent.core;

import java.time.Instant;
import java.util.List;

import com.pijava.ai.catalog.InMemoryModelsStore;
import com.pijava.ai.catalog.ModelsPublication;
import com.pijava.ai.catalog.ModelsStoreEntry;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 原 docs/70 §1.4／§7.1-8：世代发布 —— **持久化先落、世代检查后判**。
 * 过期刷新的写入仍然落盘，但 {@code update} 不跑、返回 false。
 */
class CatalogRefreshCoordinatorTest {

    private static final ModelsStoreEntry ENTRY =
        new ModelsStoreEntry(List.of(), Instant.parse("2026-06-01T00:00:00Z"),
            Instant.parse("2026-06-01T00:00:00Z"), "\"v\"");

    @Test
    void matchingGenerationRunsUpdateAndReturnsTrue() {
        var store = new InMemoryModelsStore();
        var coordinator = new CatalogRefreshCoordinator(store);
        var generation = coordinator.begin("openai");
        var updates = new java.util.concurrent.atomic.AtomicInteger();

        var accepted = coordinator.publish("openai", generation,
            ModelsPublication.persistAndUpdate(ENTRY, updates::incrementAndGet));

        assertThat(accepted).isTrue();
        assertThat(updates.get()).isEqualTo(1);
        assertThat(store.read("openai")).contains(ENTRY);
    }

    @Test
    void staleGenerationStillPersistsButSkipsUpdateAndReturnsFalse() {
        var store = new InMemoryModelsStore();
        var coordinator = new CatalogRefreshCoordinator(store);
        var stale = coordinator.begin("openai");
        coordinator.begin("openai"); // 后发的刷新拿到了新世代
        var updates = new java.util.concurrent.atomic.AtomicInteger();

        var accepted = coordinator.publish("openai", stale,
            ModelsPublication.persistAndUpdate(ENTRY, updates::incrementAndGet));

        assertThat(accepted).isFalse();
        assertThat(updates.get()).isZero();
        // pi 的次序：落存储发生在世代检查之前
        assertThat(store.read("openai")).contains(ENTRY);
    }

    @Test
    void updateOnlyPublicationLeavesStoreUntouched() {
        var store = new InMemoryModelsStore();
        var coordinator = new CatalogRefreshCoordinator(store);
        var generation = coordinator.begin("openai");
        var updates = new java.util.concurrent.atomic.AtomicInteger();

        coordinator.publish("openai", generation,
            ModelsPublication.updateOnly(updates::incrementAndGet));

        assertThat(updates.get()).isEqualTo(1);
        assertThat(store.read("openai")).isEmpty();
    }

    @Test
    void deletePublicationRemovesTheEntry() {
        var store = new InMemoryModelsStore();
        store.write("openai", ENTRY);
        var coordinator = new CatalogRefreshCoordinator(store);
        var generation = coordinator.begin("openai");

        var accepted = coordinator.publish("openai", generation,
            ModelsPublication.remove());

        assertThat(accepted).isTrue();
        assertThat(store.read("openai")).isEmpty();
    }

    @Test
    void generationsArePerProvider() {
        var store = new InMemoryModelsStore();
        var coordinator = new CatalogRefreshCoordinator(store);
        var openai = coordinator.begin("openai");
        coordinator.begin("anthropic");

        assertThat(coordinator.publish("openai", openai,
            ModelsPublication.persist(ENTRY))).isTrue();
    }
}
