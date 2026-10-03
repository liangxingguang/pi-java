package com.pijava.coding.agent.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.pijava.ai.catalog.ModelsPublication;
import com.pijava.ai.catalog.ModelsStore;

/**
 * 目录刷新的世代协调器（pi {@code ModelRuntime.beginProviderRefresh} ＋
 * {@code publishProviderModels}，docs/70 §1.4/§4.5）。
 *
 * <p>每次刷新相开始前 {@link #begin(String)} 拿一个世代号；该相的 publish 请求
 * 带上它。**持久化先落，世代检查后判**（pi 的次序）：过期刷新的写入仍然落盘，
 * 但 {@code update} 不跑、返回 false —— 这样后发的刷新不会被先发的覆盖内存态。</p>
 */
public final class CatalogRefreshCoordinator {

    private final ModelsStore store;
    private final Map<String, Integer> generations = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public CatalogRefreshCoordinator(ModelsStore store) {
        this.store = store;
    }

    /** 开启一个刷新相：世代号自增并返回新值（pi {@code beginProviderRefresh}）。 */
    public int begin(String providerId) {
        return generations.merge(providerId, 1, Integer::sum);
    }

    /**
     * pi {@code publishProviderModels}：先按 publication 落存储，再判世代 ——
     * 世代不再匹配则更新内存态这一步跳过、返回 false。
     */
    public boolean publish(String providerId, int generation, ModelsPublication publication) {
        synchronized (lock(providerId)) {
            if (publication.delete()) {
                store.delete(providerId);
            }
            publication.persist().ifPresent(entry -> store.write(providerId, entry));
            if (currentGeneration(providerId) != generation) {
                return false;
            }
            publication.update().run();
            return true;
        }
    }

    private int currentGeneration(String providerId) {
        return generations.getOrDefault(providerId, 0);
    }

    private Object lock(String providerId) {
        return locks.computeIfAbsent(providerId, key -> new Object());
    }
}
