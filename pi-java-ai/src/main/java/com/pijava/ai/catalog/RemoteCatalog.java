package com.pijava.ai.catalog;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.pijava.ai.model.ModelId;

/**
 * 从远端 JSON 拉取模型目录的 {@link ModelCatalog}（ETag 条件刷新，离线回退缓存）。
 *
 * <p>行为（对齐 pi 模型目录同步）：{@link #refresh()} 读本地缓存 + ETag，发
 * {@code If-None-Match}；{@code 304} 用缓存；{@code 200} 解析新 JSON 并覆盖缓存；
 * 网络失败回退缓存。ETag 含引号原样存储、原样回填。</p>
 */
public final class RemoteCatalog implements ModelCatalog {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String providerName;
    private final URL source;
    private final ModelsStore store;
    private volatile List<ModelInfo> models = List.of();

    /**
     * @param providerName provider ID（缓存键）
     * @param source       远端目录 URL
     * @param store        缓存存储
     */
    public RemoteCatalog(String providerName, URL source, ModelsStore store) {
        this.providerName = providerName;
        this.source = source;
        this.store = store;
    }

    /** 启动/定时刷新；304 时使用本地缓存。 */
    public CatalogRefreshResult refresh() {
        return refreshWithEtag(true);
    }

    /** 忽略 ETag 强制刷新。 */
    public CatalogRefreshResult forceRefresh() {
        return refreshWithEtag(false);
    }

    private CatalogRefreshResult refreshWithEtag(boolean conditional) {
        var cached = store.read(providerName);
        try {
            var conn = (HttpURLConnection) source.openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            conn.setRequestProperty("Accept", "application/json");
            if (conditional && cached.isPresent() && cached.get().etag() != null) {
                conn.setRequestProperty("If-None-Match", cached.get().etag());
            }
            int status = conn.getResponseCode();
            if (status == HttpURLConnection.HTTP_NOT_MODIFIED && cached.isPresent()) {
                return useCache(cached.get());
            }
            if (status == HttpURLConnection.HTTP_OK) {
                return ingest(conn, cached);
            }
            return fallback(cached, "HTTP " + status);
        } catch (IOException e) {
            return fallback(cached, e.getMessage());
        }
    }

    private CatalogRefreshResult ingest(HttpURLConnection conn, Optional<ModelsStoreEntry> cached)
            throws IOException {
        CatalogModel[] dtos = JSON.readValue(conn.getInputStream(), CatalogModel[].class);
        var models = new ArrayList<ModelInfo>();
        for (var dto : dtos) {
            models.add(dto.toModelInfo());
        }
        this.models = List.copyOf(models);
        // ETag 原样存储（含引号）
        String etag = conn.getHeaderField("ETag");
        Instant lastModified = parseHttpDate(conn.getHeaderField("Last-Modified"));
        store.write(providerName,
            new ModelsStoreEntry(this.models, lastModified, Instant.now(), etag));
        return new CatalogRefreshResult(true, models.size(), etag, Instant.now());
    }

    private CatalogRefreshResult useCache(ModelsStoreEntry entry) {
        this.models = entry.models();
        return new CatalogRefreshResult(false, entry.models().size(), entry.etag(), Instant.now());
    }

    private CatalogRefreshResult fallback(Optional<ModelsStoreEntry> cached, String reason) {
        if (cached.isPresent()) {
            return useCache(cached.get());
        }
        throw new UncheckedIOException(new IOException(
            "Remote catalog " + source + " unavailable (" + reason
                + ") and no cached models"));
    }

    // ── ModelCatalog ─────────────────────────────────────────────────────

    @Override
    public List<ModelInfo> listModels() {
        if (models.isEmpty()) {
            refresh();
        }
        return models;
    }

    @Override
    public Optional<ModelInfo> find(ModelId<?> id) {
        return listModels().stream()
            .filter(m -> m.id().equals(id))
            .findFirst();
    }

    @Override
    public List<ModelInfo> search(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String q = query.toLowerCase(Locale.ROOT);
        return listModels().stream()
            .filter(m -> m.id().provider().toLowerCase(Locale.ROOT).contains(q)
                || m.id().modelName().toLowerCase(Locale.ROOT).contains(q)
                || m.displayName().toLowerCase(Locale.ROOT).contains(q))
            .toList();
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /** HTTP-date → Instant；解析失败返回 null。 */
    private static Instant parseHttpDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant();
        } catch (Exception e) {
            return null;
        }
    }
}
