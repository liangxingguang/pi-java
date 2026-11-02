package com.pijava.ai.provider.builtin;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.pijava.ai.AbortSignal;
import com.pijava.ai.auth.RecordedCredential;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.catalog.RefreshModelsContext;
import com.pijava.ai.model.ModelCapability;

/**
 * OpenRouter 按 Key 收窄目录（pi {@code personalizeOpenRouterCatalog}，
 * {@code providers/openrouter.ts:14-88}）。
 *
 * <p>用调用方凭据请求 {@code /models/user}：Key 可用的 chat 模型之外全部删除，
 * 限额/价格用账号值覆盖；401/403/404 原样返回（代理可能没有该端点），
 * 其他失败响亮抛错。没有 Key 时不发请求。</p>
 */
public final class OpenRouterPersonalization {

    public static final String DEFAULT_BASE_URL = "https://openrouter.ai/api/v1";

    public static final Duration USER_MODELS_TIMEOUT = Duration.ofSeconds(15);

    private static final ObjectMapper JSON = new ObjectMapper();

    private OpenRouterPersonalization() {}

    /** 从刷新上下文取凭据/端点/信号。 */
    public static List<ModelInfo> personalize(List<ModelInfo> catalog,
                                              RefreshModelsContext context,
                                              HttpClient http) {
        return personalize(catalog, context.credential(), context.baseUrl(),
            context.signal(), http);
    }

    static List<ModelInfo> personalize(List<ModelInfo> catalog,
                                       Optional<RecordedCredential> credential,
                                       String baseUrl, AbortSignal signal,
                                       HttpClient http) {
        var key = credential.map(RecordedCredential::value)
            .filter(value -> !value.isBlank()).orElse(null);
        if (key == null || signal.isAborted()) {
            return catalog;
        }

        var response = request(key, baseUrl, http);
        if (signal.isAborted()) {
            return catalog;
        }
        int status = response.statusCode();
        if (status == 401 || status == 403 || status == 404) {
            return catalog;
        }
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("OpenRouter model list request failed: " + status);
        }

        var allowed = parseAllowed(response.body());
        var personalized = new ArrayList<ModelInfo>(catalog.size());
        for (var model : catalog) {
            if (!isChat(model)) {
                personalized.add(model);
                continue;
            }
            var entry = allowed.get(model.id().modelName());
            if (entry == null) {
                continue;
            }
            personalized.add(applyEntry(model, entry));
        }
        return List.copyOf(personalized);
    }

    private static HttpResponse<String> request(String key, String baseUrl, HttpClient http) {
        var base = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl;
        var uri = URI.create(stripTrailingSlashes(base) + "/models/user");
        var request = HttpRequest.newBuilder(uri)
            .timeout(USER_MODELS_TIMEOUT)
            .header("accept", "application/json")
            .header("authorization", "Bearer " + key)
            .GET()
            .build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException(
                "OpenRouter model list request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OpenRouter model list request interrupted", e);
        }
    }

    private static Map<String, JsonNode> parseAllowed(String body) {
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (IOException e) {
            throw new IllegalStateException("Invalid OpenRouter model list response", e);
        }
        if (root == null || !root.isObject() || !root.path("data").isArray()) {
            throw new IllegalStateException("Invalid OpenRouter model list response");
        }
        var allowed = new HashMap<String, JsonNode>();
        for (var entry : root.path("data")) {
            if (entry.isObject() && entry.path("id").isTextual()) {
                allowed.put(entry.path("id").textValue(), entry);
            }
        }
        return allowed;
    }

    private static ModelInfo applyEntry(ModelInfo model, JsonNode entry) {
        int contextWindow = contextWindow(entry, model.maxInputTokens());
        int maxTokens = maxCompletionTokens(entry, model.maxOutputTokens());
        var pricing = entry.has("pricing") && entry.get("pricing").isObject()
            ? OpenRouterCost.fromWire(entry.get("pricing"))
            : model.pricing();
        return model.withLimitsAndPricing(contextWindow, maxTokens, pricing);
    }

    private static int contextWindow(JsonNode entry, int fallback) {
        int top = entry.path("top_provider").path("context_length").asInt(0);
        if (top > 0) {
            return top;
        }
        int raw = entry.path("context_length").asInt(0);
        return raw > 0 ? raw : fallback;
    }

    private static int maxCompletionTokens(JsonNode entry, int fallback) {
        int top = entry.path("top_provider").path("max_completion_tokens").asInt(0);
        return top > 0 ? top : fallback;
    }

    /** Java 的图片/分类器模型没有 TEXT 能力位；只收窄 chat 列表。 */
    private static boolean isChat(ModelInfo model) {
        return model.capabilities().contains(ModelCapability.TEXT);
    }

    private static String stripTrailingSlashes(String value) {
        var result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
