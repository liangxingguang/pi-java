package com.pijava.coding.agent.core;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.pijava.ai.AbortSignal;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.catalog.RemoteModelWire;

/**
 * pi.dev 目录的共享 wire 逻辑（pi {@code remote-catalog-provider.ts} 的纯函数）：
 * 合并、解析、请求与新鲜度判断。{@link RemoteCatalogProvider} 与
 * {@link PersonalizedCatalogProvider} 共用，避免两份解析漂移。
 */
final class RemoteCatalogWire {

    static final Duration ATTEMPT_TIMEOUT = Duration.ofSeconds(4);

    static final String USER_AGENT = "pi-java/dev";

    private static final ObjectMapper JSON = new ObjectMapper();

    private RemoteCatalogWire() {}

    /** pi {@code mergeModels}：baseline 保序；动态同 id 覆盖、新 id 追加。 */
    static List<ModelInfo> mergeModels(List<ModelInfo> baseline, List<ModelInfo> dynamic) {
        var merged = new ArrayList<ModelInfo>(baseline);
        for (var model : dynamic) {
            var index = indexOfId(merged, model.id());
            if (index >= 0) {
                merged.set(index, model);
            } else {
                merged.add(model);
            }
        }
        return List.copyOf(merged);
    }

    private static int indexOfId(List<ModelInfo> models, com.pijava.ai.model.ModelId<?> id) {
        for (int i = 0; i < models.size(); i++) {
            if (models.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    /** pi {@code parseCatalog}：数组／{@code {models:[]}}／任意对象取 values。 */
    static List<ModelInfo> parseCatalog(String providerId, String body) {
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (IOException e) {
            throw new IllegalStateException(
                "Invalid model catalog for provider \"" + providerId + "\"", e);
        }
        List<JsonNode> entries;
        if (root != null && root.isArray()) {
            entries = drain(root.elements());
        } else if (root != null && root.isObject()
                && root.has("models") && root.get("models").isArray()) {
            entries = drain(root.get("models").elements());
        } else if (root != null && root.isObject()) {
            entries = drain(root.elements());
        } else {
            throw new IllegalStateException(
                "Invalid model catalog for provider \"" + providerId + "\"");
        }
        var models = new ArrayList<ModelInfo>();
        for (var node : entries) {
            if (node.isObject() && node.has("id")) {
                models.add(JSON.convertValue(node, RemoteModelWire.class)
                    .toModelInfo(providerId));
            }
        }
        return List.copyOf(models);
    }

    private static List<JsonNode> drain(Iterator<JsonNode> nodes) {
        var out = new ArrayList<JsonNode>();
        nodes.forEachRemaining(out::add);
        return out;
    }

    /** pi {@code isNewer}：没有本地生成时刻 ⇒ 远端总是可用。 */
    static boolean isNewer(Instant remote, Instant local) {
        return local == null || (remote != null && remote.isAfter(local));
    }

    /**
     * 请求某 provider 的 pi.dev 目录（无条件 GET，4s attempt timeout）。
     * 当前没有生产取消路径；发请求前后检查 {@code signal}。
     */
    static HttpResponse<String> requestCatalog(String providerId, String catalogBaseUrl,
                                               AbortSignal signal, HttpClient http) {
        signal.throwIfAborted();
        var base = catalogBaseUrl == null || catalogBaseUrl.isBlank()
            ? RemoteCatalogProvider.DEFAULT_CATALOG_BASE_URL : catalogBaseUrl;
        var uri = URI.create(base).resolve("/api/models/providers/" + encode(providerId));
        var request = HttpRequest.newBuilder(uri)
            .timeout(ATTEMPT_TIMEOUT)
            .header("accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .GET()
            .build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(
                "Model catalog request failed for " + providerId, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                "Model catalog request interrupted for " + providerId, e);
        }
    }

    /** Last-Modified（RFC 1123）⇒ Instant；缺席／不可解析 ⇒ {@code Instant.EPOCH}（pi 的 0）。 */
    static Instant lastModifiedOf(HttpResponse<String> response) {
        var raw = response.headers().firstValue("last-modified").orElse(null);
        if (raw == null) {
            return Instant.EPOCH;
        }
        try {
            return ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (DateTimeParseException e) {
            return Instant.EPOCH;
        }
    }

    static String etagOf(HttpResponse<String> response) {
        return response.headers().firstValue("etag").orElse(null);
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
