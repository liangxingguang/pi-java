package com.pijava.ai.catalog;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 模型目录发布工具 —— 校验 / 合并 / ETag 生成 / HTTP PUT 上传（设计 §7.2）。
 *
 * <p>wire 格式为 {@link CatalogModel} 数组（与 {@code RemoteCatalog} 一致）。</p>
 */
public final class CatalogPublisher {

    private static final ObjectMapper JSON = new ObjectMapper();

    private CatalogPublisher() {
    }

    // ── 校验 ─────────────────────────────────────────────────────────────

    /** 校验模型列表；返回错误描述（空 = 合法）。 */
    public static List<String> validate(List<CatalogModel> models) {
        var errors = new ArrayList<String>();
        if (models == null || models.isEmpty()) {
            errors.add("models list is empty");
            return errors;
        }
        var seen = new java.util.HashSet<String>();
        for (var model : models) {
            if (model.provider() == null || model.provider().isBlank()) {
                errors.add("model with blank provider: " + model);
            }
            if (model.model() == null || model.model().isBlank()) {
                errors.add("model with blank id: " + model);
            }
            if (model.maxInputTokens() < 0 || model.maxOutputTokens() < 0) {
                errors.add("negative token limit: " + model.model());
            }
            String key = model.provider() + "/" + model.model();
            if (!seen.add(key)) {
                errors.add("duplicate model: " + key);
            }
        }
        return errors;
    }

    // ── 合并 ─────────────────────────────────────────────────────────────

    /**
     * 合并 base 与 overlay（overlay 按 provider/model 覆盖 base），保序。
     */
    public static List<CatalogModel> merge(List<CatalogModel> base,
                                           List<CatalogModel> overlay) {
        var byKey = new LinkedHashMap<String, CatalogModel>();
        for (var model : base) {
            byKey.put(key(model), model);
        }
        for (var model : overlay) {
            byKey.put(key(model), model);
        }
        return List.copyOf(byKey.values());
    }

    // ── ETag ─────────────────────────────────────────────────────────────

    /** 基于内容生成 ETag（SHA-256，含引号 —— 与 RemoteCatalog 原样存储一致）。 */
    public static String generateEtag(byte[] content) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(content);
            return "\"" + HexFormat.of().formatHex(digest) + "\"";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // ── 上传 ─────────────────────────────────────────────────────────────

    /** HTTP PUT 上传到静态托管端点（S3 兼容接口可用 PUT）。 */
    public static void publish(URI endpoint, byte[] content, String etag)
            throws IOException {
        URL url = endpoint.toURL();
        var conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("PUT");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("ETag", etag);
        conn.setDoOutput(true);
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(10_000);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(content);
        }
        int status = conn.getResponseCode();
        if (status < 200 || status >= 300) {
            throw new IOException("Publish failed: HTTP " + status
                + " " + conn.getResponseMessage());
        }
    }

    // ── 序列化辅助 ───────────────────────────────────────────────────────

    /** 序列化模型列表为 JSON 字节。 */
    public static byte[] toJsonBytes(List<CatalogModel> models) {
        try {
            return JSON.writeValueAsBytes(models);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot serialize catalog", e);
        }
    }

    /** 解析 models.json 字节为模型列表。 */
    public static List<CatalogModel> parse(byte[] content) {
        try {
            return List.of(JSON.readValue(content, CatalogModel[].class));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid catalog JSON", e);
        }
    }

    private static String key(CatalogModel model) {
        return model.provider() + "/" + model.model();
    }
}
