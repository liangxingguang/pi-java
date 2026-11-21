package com.pijava.ai.protocol;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.pijava.ai.AbortSignal;
import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ImageApi;
import com.pijava.ai.api.ImageRequest;
import com.pijava.ai.api.ImageResult;
import com.pijava.ai.api.ImageStopReason;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.utils.ProviderEnv;
import com.pijava.ai.utils.ProviderErrors;
import com.pijava.ai.utils.SanitizeUnicode;

/**
 * OpenAI Images API adapter for {@code POST /images/generations}.
 *
 * <p>Independent of the chat/completions lanes: the OpenAI Java SDK pinned by
 * this repository has no images models, so this adapter speaks the JSON wire
 * directly. Request and response shape follow the OpenAI Images API:
 * {@code prompt/n/size} in, {@code data[].b64_json} or {@code data[].url} out.
 * Generation with image input is not supported by this endpoint; text blocks
 * in {@link ImageRequest#input()} are joined into the prompt and image blocks
 * are ignored.</p>
 */
public final class OpenAIImagesApi implements ImageApi {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";

    private final String apiKey;
    private final String baseUrl;
    private final Duration timeout;
    private final HttpClient client;

    /** @param options     API options (apiKey or {@code OPENAI_API_KEY})
     *  @param apiKeyEnvVar env var name used when options.apiKey is blank */
    public OpenAIImagesApi(ApiOptions options, String apiKeyEnvVar) {
        this.apiKey = resolveApiKey(options, apiKeyEnvVar);
        var configured = options.baseUrl();
        this.baseUrl = configured != null && !configured.isBlank()
            ? configured : DEFAULT_BASE_URL;
        var t = options.timeout();
        this.timeout = t == null ? Duration.ofSeconds(60) : t;
        this.client = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }

    @Override
    public ImageResult generate(ImageRequest request, ApiOptions options) {
        long timestamp = System.currentTimeMillis();
        try {
            var body = buildBody(request, options);
            var headers = new LinkedHashMap<String, String>();
            // identity／extra["headers"] 先落，apiKey 与 Content-Type 后写覆盖；
            // null 值在 pi 语义里表示“删除该头”，HttpRequest 不接受 null。
            AbstractChatApi.putRequestHeaders(
                options == null ? ApiOptions.defaults() : options,
                (name, value) -> {
                    if (name != null && value != null) {
                        headers.put(name, value);
                    }
                });
            headers.put("Authorization", "Bearer " + apiKey);
            headers.put("Content-Type", "application/json");

            var httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl.replaceAll("/+$", "") + "/images/generations"))
                .timeout(timeout)
                .headers(headers.entrySet().stream()
                    .flatMap(e -> java.util.stream.Stream.of(e.getKey(), e.getValue()))
                    .toArray(String[]::new))
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body),
                    StandardCharsets.UTF_8))
                .build();
            var response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString(
                StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return failure(request, timestamp, options, errorMessage(response));
            }
            return new ImageResult(request.model().provider(), request.model().modelName(),
                parseImages(JSON.readTree(response.body())), ImageStopReason.STOP, null,
                timestamp);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failure(request, timestamp, options, "aborted");
        } catch (Exception e) {
            return failure(request, timestamp, options, e.getMessage() == null
                ? e.toString() : e.getMessage());
        }
    }

    /** Request body for {@code /images/generations} (package-private for tests). */
    Map<String, Object> buildBody(ImageRequest request, ApiOptions options) {
        var prompt = new StringBuilder();
        for (var block : request.input()) {
            if (block instanceof ContentBlock.TextContent text) {
                if (prompt.length() > 0) {
                    prompt.append('\n');
                }
                prompt.append(SanitizeUnicode.surrogates(text.text()));
            }
            // OpenAI Images 端点不接受图片输入；图片块直接忽略。
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("model", request.model().modelName());
        body.put("prompt", prompt.toString());
        body.put("n", 1);
        var size = options != null ? options.extra().get("size") : null;
        body.put("size", size instanceof String s && !s.isBlank() ? s : "1024x1024");
        // gpt-image-1 只返回 b64_json；dall-e 系列需要显式 response_format。
        if (request.model().modelName().startsWith("dall-e")) {
            body.put("response_format", "b64_json");
        }
        return body;
    }

    /** Parse {@code data[].b64_json} / {@code data[].url} (package-private for tests). */
    static List<ContentBlock> parseImages(JsonNode root) {
        var out = new ArrayList<ContentBlock>();
        if (root == null || !root.path("data").isArray()) {
            return out;
        }
        ArrayNode data = (ArrayNode) root.path("data");
        var format = root.path("output_format").asText("png");
        String mediaType = format.startsWith("image/") ? format : "image/" + format;
        for (JsonNode item : data) {
            var b64 = item.path("b64_json").asText(null);
            if (b64 != null && !b64.isBlank()) {
                out.add(new ContentBlock.ImageContent(mediaType, b64));
                continue;
            }
            var url = item.path("url").asText(null);
            if (url != null && !url.isBlank()) {
                out.add(new ContentBlock.UrlImageContent(url));
            }
        }
        return out;
    }

    private static ImageResult failure(ImageRequest request, long timestamp,
                                       ApiOptions options, String message) {
        boolean aborted = options != null
            && options.extra().get("signal") instanceof AbortSignal signal
            && signal.isAborted();
        return new ImageResult(request.model().provider(), request.model().modelName(),
            List.of(), aborted ? ImageStopReason.ABORTED : ImageStopReason.ERROR,
            message, timestamp);
    }

    private static String errorMessage(HttpResponse<String> response) {
        int status = response.statusCode();
        var raw = response.body() == null ? "" : response.body().trim();
        if (raw.isEmpty()) {
            return status + " status code (no body)";
        }
        try {
            var node = JSON.readTree(raw);
            // pi extractBody：只取 error 节点（SDK body 已是嵌套节点），
            // makeMessage 用 status + 节点 message，两者分开喂 normalize/format。
            var errorNode = node.has("error") ? node.path("error") : node;
            var body = JSON.writeValueAsString(errorNode);
            var messageNode = errorNode.path("message");
            String text = messageNode.isTextual() && !messageNode.asText().isEmpty()
                ? messageNode.asText() : body;
            return ProviderErrors.format(ProviderErrors.normalize(status, body,
                status + " " + text));
        } catch (Exception ignored) {
            // 非 JSON 错误体：pi makeMessage 把原文接在 status 后面。
            return ProviderErrors.format(ProviderErrors.normalize(status, raw,
                status + " " + raw));
        }
    }

    private static String resolveApiKey(ApiOptions options, String envVar) {
        if (options.apiKey() != null && !options.apiKey().isBlank()) {
            return options.apiKey();
        }
        var env = ProviderEnv.value(envVar, options.env());
        if (env != null && !env.isBlank()) {
            return env;
        }
        throw new IllegalStateException("No API key. Set " + envVar + " or pass apiKey.");
    }
}
