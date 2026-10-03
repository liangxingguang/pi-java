package com.pijava.coding.agent.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.pijava.ai.catalog.ModelsStore;
import com.pijava.ai.catalog.RefreshModelsContext;
import com.pijava.ai.provider.Provider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.coding.agent.cli.Args;

/**
 * 目录刷新的两阶段编排（pi {@code ModelRuntime.refresh}，docs/70 §1.5/§4.6）。
 *
 * <p>**先全部离线恢复（{@code allowNetwork=false}），再（允许联网时）全部联网** ——
 * 保证无网/慢网时缓存 overlay 立即可用。每个 provider 一个虚拟线程；单个 provider
 * 的失败记录进结果而不打断其他 provider（pi 用 {@code Promise.all}，但
 * {@code refreshModels} 的错误在 pi 里也会 reject 整批 —— 本仓选择逐 provider 记账，
 * 判定见 docs/70 §12）。</p>
 */
public final class ModelCatalogRefresh {

    /** 一次 refresh 的结果：provider id → 它抛出的异常（成功的 provider 不在表里）。 */
    public record Result(Map<String, RuntimeException> errors) {
        public boolean hasErrors() {
            return !errors.isEmpty();
        }
    }

    private final ModelsStore store;
    private final CatalogRefreshCoordinator coordinator;

    public ModelCatalogRefresh(ModelsStore store) {
        this.store = store;
        this.coordinator = new CatalogRefreshCoordinator(store);
    }

    /** 两阶段刷新；{@code allowNetwork=false} 时只有离线相。 */
    public Result refresh(List<Provider> providers, boolean allowNetwork, boolean force) {
        var errors = new LinkedHashMap<String, RuntimeException>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            runPhase(executor, providers, false, false, errors);
            if (allowNetwork) {
                runPhase(executor, providers, true, force, errors);
            }
        }
        return new Result(Collections.unmodifiableMap(errors));
    }

    /**
     * RPC 模式启动后的后台刷新（pi {@code main.ts:924-932}）：**不阻塞启动**、
     * 错误吞掉（pi 的 {@code .catch(() => {})}）。离线时什么都不做。
     */
    public static void startBackground(ProviderRegistry registry, Args args) {
        if (offlineMode(args, System.getenv("PI_OFFLINE"))) {
            return;
        }
        var store = com.pijava.ai.catalog.FileModelsStore.defaultStore();
        var providers = registry.listAll();
        Thread.startVirtualThread(() -> {
            try {
                new ModelCatalogRefresh(store).refresh(providers, true, false);
            } catch (Exception ignored) {
                // pi: .catch(() => {}) —— 启动期刷新失败不打断会话
            }
        });
    }

    /** pi {@code main.ts:569}：{@code --offline} 或真值的 {@code PI_OFFLINE}。 */
    public static boolean offlineMode(Args args, String piOfflineEnv) {
        return args.offline() || truthyEnvFlag(piOfflineEnv);
    }

    /** pi {@code isTruthyEnvFlag}：{@code "1"}／{@code "true"}／{@code "yes"}（大小写不敏感）。 */
    static boolean truthyEnvFlag(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        return "1".equals(value)
            || "true".equalsIgnoreCase(value)
            || "yes".equalsIgnoreCase(value);
    }

    private void runPhase(ExecutorService executor, List<Provider> providers,
                          boolean allowNetwork, boolean force,
                          Map<String, RuntimeException> errors) {
        var futures = new ArrayList<Future<?>>();
        for (var provider : providers) {
            futures.add(executor.submit(() -> {
                try {
                    runOne(provider, allowNetwork, force);
                } catch (RuntimeException e) {
                    errors.put(provider.name(), e);
                }
            }));
        }
        for (var future : futures) {
            try {
                future.get();
            } catch (ExecutionException e) {
                // runOne 的 RuntimeException 已在 worker 内吃掉；这里只剩 Error
                throw new IllegalStateException("model catalog refresh task failed", e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("model catalog refresh interrupted", e);
            }
        }
    }

    private void runOne(Provider provider, boolean allowNetwork, boolean force) {
        int generation = coordinator.begin(provider.name());
        var stored = store.read(provider.name());
        com.pijava.ai.catalog.CatalogPublisherPort publish =
            publication -> coordinator.publish(provider.name(), generation, publication);
        var context = allowNetwork
            ? RefreshModelsContext.online(stored, publish, force)
            : RefreshModelsContext.offline(stored, publish);
        provider.refreshModels(context);
    }
}
