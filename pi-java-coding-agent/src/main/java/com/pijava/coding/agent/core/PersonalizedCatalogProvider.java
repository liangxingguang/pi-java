package com.pijava.coding.agent.core;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ProviderApi;
import com.pijava.ai.catalog.ModelCatalog;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.catalog.ModelsPublication;
import com.pijava.ai.catalog.ModelsStoreEntry;
import com.pijava.ai.catalog.RefreshModelsContext;
import com.pijava.ai.provider.Provider;
import com.pijava.ai.provider.builtin.OpenRouterPersonalization;

/**
 * 先合并 pi.dev 目录、再按调用方 Key 收窄的包装（pi
 * {@code withPersonalizedCatalog}，{@code remote-catalog-provider.ts:195-240}）。
 *
 * <p>与 {@link RemoteCatalogProvider} 的差异（逐条来自 pi）：
 * <ul>
 *   <li>不做 TTL/ETag 短路：每次联网刷新都请求 pi.dev；</li>
 *   <li>只有 {@code Last-Modified} 晚于内置数据生成时刻才采用远端条目；</li>
 *   <li>持久化的是个性化后的列表（按 Key 收窄），不是 pi.dev 原始目录；</li>
 *   <li>个性化列表为空时回退内置目录。</li>
 * </ul></p>
 */
public final class PersonalizedCatalogProvider implements Provider {

    private final Provider delegate;
    private final URI catalogBaseUrl;
    private final Instant localGeneratedAt;
    private final HttpClient http;

    private volatile List<ModelInfo> personalModels = List.of();

    private PersonalizedCatalogProvider(Provider delegate, URI catalogBaseUrl,
                                        Instant localGeneratedAt, HttpClient http) {
        this.delegate = delegate;
        this.catalogBaseUrl = catalogBaseUrl;
        this.localGeneratedAt = localGeneratedAt;
        this.http = http;
    }

    /** 包装一个内置 provider；{@code localGeneratedAt} 缺席 ⇒ 远端恒被采用。 */
    public static Provider wrap(Provider delegate, String catalogBaseUrl,
                                Optional<Instant> localGeneratedAt, HttpClient http) {
        var base = catalogBaseUrl == null || catalogBaseUrl.isBlank()
            ? RemoteCatalogProvider.DEFAULT_CATALOG_BASE_URL : catalogBaseUrl;
        return new PersonalizedCatalogProvider(delegate, URI.create(base),
            localGeneratedAt == null ? null : localGeneratedAt.orElse(null), http);
    }

    // ── Provider 委派 ─────────────────────────────────────────

    @Override public String name() { return delegate.name(); }

    @Override public String displayName() { return delegate.displayName(); }

    @Override public Set<Class<? extends ProviderApi>> supportedApis() {
        return delegate.supportedApis();
    }

    @Override public <T extends ProviderApi> T createApi(Class<T> apiType, ApiOptions options) {
        return delegate.createApi(apiType, options);
    }

    @Override public ModelCatalog builtinModels() { return delegate.builtinModels(); }

    @Override public String baseUrl() { return delegate.baseUrl(); }

    @Override public Set<com.pijava.ai.provider.Protocol> supportedProtocols() {
        return delegate.supportedProtocols();
    }

    // ── 动态协议 ──────────────────────────────────────────────

    @Override
    public List<ModelInfo> getModels() {
        var current = personalModels;
        return current.isEmpty() ? delegate.getModels() : current;
    }

    @Override
    public void refreshModels(RefreshModelsContext context) {
        var restored = context.stored()
            .map(entry -> providerModels(entry.models()))
            .orElse(List.of());
        if (!context.publish().publish(ModelsPublication.updateOnly(
                () -> personalModels = restored))) {
            return;
        }
        if (!context.allowNetwork() || context.signal().isAborted()) {
            return;
        }

        var response = RemoteCatalogWire.requestCatalog(
            delegate.name(), catalogBaseUrl.toString(), context.signal(), http);
        if (context.signal().isAborted()) {
            return;
        }
        List<ModelInfo> remote = List.of();
        int status = response.statusCode();
        if (status != 404 && status != 501) {
            if (status < 200 || status >= 300) {
                throw new IllegalStateException(
                    "Model catalog request failed for " + delegate.name() + ": " + status);
            }
            var catalog = RemoteCatalogWire.parseCatalog(delegate.name(), response.body());
            if (RemoteCatalogWire.isNewer(
                    RemoteCatalogWire.lastModifiedOf(response), localGeneratedAt)) {
                remote = catalog;
            }
        }

        var merged = RemoteCatalogWire.mergeModels(
            delegate.builtinModels().listModels(), remote);
        var personalized = OpenRouterPersonalization.personalize(merged, context, http);
        if (context.signal().isAborted()) {
            return;
        }
        var entry = new ModelsStoreEntry(personalized, null, Instant.now(), null);
        context.publish().publish(ModelsPublication.persistAndUpdate(
            entry, () -> personalModels = personalized));
    }

    private List<ModelInfo> providerModels(List<ModelInfo> models) {
        return models.stream()
            .filter(model -> model.id().provider().equals(delegate.name()))
            .toList();
    }
}
