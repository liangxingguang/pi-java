package com.pijava.coding.agent.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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

/**
 * pi.dev 远程目录覆盖包装（pi {@code core/remote-catalog-provider.ts} 的
 * {@code withRemoteCatalog}，原 docs/70 §1.3）。
 *
 * <p>{@link #getModels()} = 静态目录 ∪ 动态 overlay（同 id 覆盖、新 id 追加）；
 * {@link #refreshModels(RefreshModelsContext)} 按 pi 的全分支刷新：离线恢复 ⇒
 * TTL 短路 ⇒ 条件 GET ⇒ 304／404／501／transient／200。</p>
 *
 * <p>与 pi 的三处刻意差异：
 * <ul>
 *   <li>{@code AbortSignal} 不移植（原 docs/70 R2）：不支持中途取消，4s attempt
 *       timeout 封顶；</li>
 *   <li>{@code fetchWithRetry} 不移植（原 docs/70 R3）：单次 JDK HttpClient 请求，
 *       不发重试；</li>
 *   <li>User-Agent 用本仓既有约定 {@code pi-java/dev}（{@code PiHttpClient} 等三处
 *       同值），不另造 pi 的 {@code pi/<version> (…)} 形状 —— 本仓没有版本源。</li>
 * </ul></p>
 */
public final class RemoteCatalogProvider implements Provider {

    /** pi {@code DEFAULT_CATALOG_BASE_URL}。 */
    public static final String DEFAULT_CATALOG_BASE_URL = "https://pi.dev";

    /** pi {@code REMOTE_CATALOG_ATTEMPT_TIMEOUT_MS}。 */
    static final Duration ATTEMPT_TIMEOUT = RemoteCatalogWire.ATTEMPT_TIMEOUT;

    /** pi {@code REMOTE_CATALOG_REFRESH_INTERVAL_MS}。 */
    public static final Duration REFRESH_INTERVAL = Duration.ofHours(4);

    private final Provider delegate;
    private final URI catalogBaseUrl;
    /** 静态目录的生成时间；不在其之后的远端 Last-Modified 被守卫掉（可为 null）。 */
    private final Instant localGeneratedAt;
    private final HttpClient http;

    private volatile List<ModelInfo> dynamic = List.of();

    private RemoteCatalogProvider(Provider delegate, URI catalogBaseUrl,
                                  Instant localGeneratedAt, HttpClient http) {
        this.delegate = delegate;
        this.catalogBaseUrl = catalogBaseUrl;
        this.localGeneratedAt = localGeneratedAt;
        this.http = http;
    }

    /**
     * 包装静态 provider。{@code localGeneratedAt} 缺席 ⇒ 守卫失效（远端恒覆盖），
     * 生产装配一律给 {@code ModelData.generatedAt()}。
     */
    public static Provider wrap(Provider delegate, String catalogBaseUrl,
                                Optional<Instant> localGeneratedAt) {
        return wrap(delegate, catalogBaseUrl, localGeneratedAt, defaultHttpClient());
    }

    /**
     * 注入 {@link HttpClient} 的形态：装配层应当**整批共用一个** —— 每包装一次就
     * 新建一个客户端会让一次刷新拉起 N 个选择器线程。
     */
    public static Provider wrap(Provider delegate, String catalogBaseUrl,
                                Optional<Instant> localGeneratedAt, HttpClient http) {
        var base = catalogBaseUrl == null || catalogBaseUrl.isBlank()
            ? DEFAULT_CATALOG_BASE_URL : catalogBaseUrl;
        return new RemoteCatalogProvider(delegate, URI.create(base),
            localGeneratedAt == null ? null : localGeneratedAt.orElse(null), http);
    }

    /** 单次包装用的默认客户端（4s 连接超时）。 */
    public static HttpClient defaultHttpClient() {
        return HttpClient.newBuilder().connectTimeout(ATTEMPT_TIMEOUT).build();
    }

    // ── Provider 委派 ─────────────────────────────────────────

    /** Authentication/catalog metadata visible through the dynamic wrapper. */
    Provider delegate() { return delegate; }

    @Override public String name() { return delegate.name(); }

    @Override public String displayName() { return delegate.displayName(); }

    @Override public Set<Class<? extends ProviderApi>> supportedApis() {
        return delegate.supportedApis();
    }

    @Override public <T extends ProviderApi> T createApi(Class<T> apiType, ApiOptions options) {
        return delegate.createApi(apiType, options);
    }

    @Override public ModelCatalog builtinModels() { return delegate.builtinModels(); }

    @Override public Set<com.pijava.ai.provider.Protocol> supportedProtocols() {
        return delegate.supportedProtocols();
    }

    @Override public String baseUrl() { return delegate.baseUrl(); }

    // ── 动态协议 ──────────────────────────────────────────────

    /** 静态 ∪ overlay：同 id 覆盖、新 id 追加（pi {@code mergeModels}）。 */
    @Override
    public List<ModelInfo> getModels() {
        return RemoteCatalogWire.mergeModels(delegate.getModels(), dynamic);
    }

    @Override
    public void refreshModels(RefreshModelsContext context) {
        var stored = context.stored().orElse(null);
        var restored = providerModels(remoteModels(stored));
        if (!context.publish().publish(ModelsPublication.updateOnly(() -> dynamic = restored))) {
            return; // 世代过期：存储与 overlay 都不动
        }
        if (!context.allowNetwork()) {
            return; // 离线相：只恢复缓存
        }
        if (context.signal().isAborted()) {
            return;
        }
        if (!context.force() && isFresh(stored)) {
            return;
        }
        fetch(context, stored);
    }

    // ── 网络相 ────────────────────────────────────────────────

    private void fetch(RefreshModelsContext context, ModelsStoreEntry cached) {
        // validator 只在有缓存 body 支撑时带 —— 否则 304 会把 overlay 清空。
        var validator = cached != null && !cached.models().isEmpty() ? cached.etag() : null;
        var uri = catalogBaseUrl.resolve(
            "/api/models/providers/" + RemoteCatalogWire.encode(delegate.name()));
        var builder = HttpRequest.newBuilder(uri)
            .timeout(ATTEMPT_TIMEOUT)
            .header("accept", "application/json")
            .header("User-Agent", RemoteCatalogWire.USER_AGENT);
        if (validator != null) {
            builder.header("If-None-Match", validator);
        }
        var response = send(builder.build());
        if (context.signal().isAborted()) {
            return;
        }
        var checkedAt = Instant.now();
        var status = response.statusCode();

        if (status == 304 && cached != null) {
            context.publish().publish(
                ModelsPublication.persist(cached.withCheckedAt(checkedAt)));
            return;
        }
        if (status == 404 || status == 501) {
            // 远端确认无此 provider：保留 models 但把 Last-Modified 归零、清 ETag，
            // 于是 remoteModels 守卫恒空且下次不再带 validator。
            var base = cached != null ? cached : ModelsStoreEntry.of(List.of());
            context.publish().publish(ModelsPublication.persist(new ModelsStoreEntry(
                base.models(), Instant.EPOCH, checkedAt, null)));
            return;
        }
        if (status < 200 || status >= 300) {
            // transient：保留 etag，让下次刷新改成条件请求而不是重下。
            var base = cached != null ? cached : ModelsStoreEntry.of(List.of());
            context.publish().publish(
                ModelsPublication.persist(base.withCheckedAt(checkedAt)));
            throw new IllegalStateException(
                "Model catalog request failed for " + delegate.name() + ": " + status);
        }

        var entry = new ModelsStoreEntry(
            RemoteCatalogWire.parseCatalog(delegate.name(), response.body()),
            RemoteCatalogWire.lastModifiedOf(response), checkedAt, RemoteCatalogWire.etagOf(response));
        var published = providerModels(remoteModels(entry));
        context.publish().publish(
            ModelsPublication.persistAndUpdate(entry, () -> dynamic = published));
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(
                "Model catalog request failed for " + delegate.name(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                "Model catalog request interrupted for " + delegate.name(), e);
        }
    }

    /** TTL：4 小时内且有过一次完成的检查 ⇒ 不发请求（pi 要求两个时间戳都在）。 */
    private static boolean isFresh(ModelsStoreEntry stored) {
        return stored != null
            && stored.checkedAt() != null
            && stored.lastModified() != null
            && stored.checkedAt().plus(REFRESH_INTERVAL).isAfter(Instant.now());
    }

    // ── 纯函数（逐条对照 pi） ─────────────────────────────────

    /**
     * pi {@code remoteModels}：无条目 ⇒ 空；有 localGeneratedAt 且远端 Last-Modified
     * 不晚于它（或缺席）⇒ 空（overlay 不生效）。
     */
    List<ModelInfo> remoteModels(ModelsStoreEntry entry) {
        if (entry == null) {
            return List.of();
        }
        if (!RemoteCatalogWire.isNewer(
                entry.lastModified(), localGeneratedAt)) {
            return List.of();
        }
        return entry.models();
    }

    private List<ModelInfo> providerModels(List<ModelInfo> models) {
        return models.stream()
            .filter(model -> model.id().provider().equals(delegate.name()))
            .toList();
    }
}
