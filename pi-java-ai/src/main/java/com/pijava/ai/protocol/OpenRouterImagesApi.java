package com.pijava.ai.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.type.TypeReference;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.models.chat.completions.ChatCompletionContentPart;
import com.openai.models.chat.completions.ChatCompletionContentPartImage;
import com.openai.models.chat.completions.ChatCompletionContentPartText;
import com.openai.models.chat.completions.ChatCompletionCreateParams;

import com.pijava.ai.AbortSignal;
import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ImageApi;
import com.pijava.ai.api.ImageRequest;
import com.pijava.ai.api.ImageResult;
import com.pijava.ai.api.ImageStopReason;
import com.pijava.ai.message.ContentBlock;

/**
 * OpenRouter 图片生成适配器（P6-28）—— 对齐 pi {@code openrouter-images.ts}：
 * chat completions + {@code modalities: ["image","text"]} 返回图片，图片在响应
 * {@code message.images}（OpenRouter 扩展，落在 additionalProperties）里以
 * data URI 给出。
 */
public final class OpenRouterImagesApi implements ImageApi {

    private static final Pattern DATA_URL = Pattern.compile("^data:([^;]+);base64,(.+)$");

    private final OpenAIClient client;
    private final String apiKey;

    /** @param options       API options（apiKey 或 {@code OPENROUTER_API_KEY}）
     *  @param apiKeyEnvVar  环境变量名（通常 "OPENROUTER_API_KEY"） */
    public OpenRouterImagesApi(ApiOptions options, String apiKeyEnvVar) {
        this.apiKey = resolveApiKey(options, apiKeyEnvVar);
        var baseUrl = options.baseUrl() != null && !options.baseUrl().isBlank()
            ? options.baseUrl() : "https://openrouter.ai/api/v1";
        this.client = OpenAIOkHttpClient.builder()
            .apiKey(apiKey).baseUrl(baseUrl).build();
    }

    @Override
    public ImageResult generate(ImageRequest request, ApiOptions options) {
        long timestamp = System.currentTimeMillis();
        try {
            var params = buildParams(request);
            var response = client.chat().completions().create(params);
            var output = new ArrayList<ContentBlock>();
            if (!response.choices().isEmpty()) {
                var message = response.choices().get(0).message();
                message.content().ifPresent(text -> {
                    if (!text.isEmpty()) {
                        output.add(new ContentBlock.TextContent(text));
                    }
                });
                output.addAll(parseImages(
                    message._additionalProperties().get("images")));
            }
            return new ImageResult(request.model().provider(), request.model().modelName(),
                output, ImageStopReason.STOP, null, timestamp);
        } catch (Exception e) {
            var aborted = options != null && isAborted(options);
            return new ImageResult(request.model().provider(), request.model().modelName(),
                List.of(),
                aborted ? ImageStopReason.ABORTED : ImageStopReason.ERROR,
                e.getMessage(), timestamp);
        }
    }

    /** 构建 chat completions 请求：user 消息 content parts + modalities（包私有供测试）。 */
    ChatCompletionCreateParams buildParams(ImageRequest request) {
        var content = request.input().stream()
            .map(this::toContentPart)
            .toList();
        return ChatCompletionCreateParams.builder()
            .model(request.model().modelName())
            .addUserMessageOfArrayOfContentParts(content)
            .modalities(List.of(
                ChatCompletionCreateParams.Modality.Companion.of("image"),
                ChatCompletionCreateParams.Modality.TEXT))
            .build();
    }

    /** ContentBlock → ChatCompletionContentPart（text / image_url data URI）。 */
    private ChatCompletionContentPart toContentPart(ContentBlock block) {
        if (block instanceof ContentBlock.TextContent t) {
            return ChatCompletionContentPart.ofText(
                ChatCompletionContentPartText.builder().text(t.text()).build());
        }
        if (block instanceof ContentBlock.ImageContent img) {
            var url = "data:" + img.mediaType() + ";base64," + img.data();
            return ChatCompletionContentPart.ofImageUrl(
                ChatCompletionContentPartImage.builder()
                    .imageUrl(ChatCompletionContentPartImage.ImageUrl.builder().url(url).build())
                    .build());
        }
        throw new IllegalArgumentException(
            "Unsupported image input block: " + block);
    }

    /** 解析 {@code message.images}：[{image_url:{url:"data:...;base64,..."}}] → ImageContent
     *  （包私有供测试）。 */
    static List<ContentBlock> parseImages(JsonValue images) {
        if (images == null) {
            return List.of();
        }
        var out = new ArrayList<ContentBlock>();
        try {
            List<Map<String, Object>> items = images.convert(
                new TypeReference<List<Map<String, Object>>>() { });
            if (items != null) {
                for (var item : items) {
                    Object raw = item.get("image_url");
                    String url;
                    if (raw instanceof String s) {
                        url = s;
                    } else if (raw instanceof Map<?, ?> m && m.get("url") instanceof String s2) {
                        url = s2;
                    } else {
                        continue;
                    }
                    if (!url.startsWith("data:")) {
                        continue;
                    }
                    var m = DATA_URL.matcher(url);
                    if (m.matches()) {
                        out.add(new ContentBlock.ImageContent(m.group(1), m.group(2)));
                    }
                }
            }
        } catch (Exception e) {
            // 畸形 images 字段忽略
        }
        return out;
    }

    private static boolean isAborted(ApiOptions options) {
        return options.extra().get("signal") instanceof AbortSignal signal
            && signal.isAborted();
    }

    /** 解析 API key：优先 options.apiKey，否则回落环境变量（同 AbstractChatApi）。 */
    private static String resolveApiKey(ApiOptions options, String envVar) {
        if (options.apiKey() != null && !options.apiKey().isBlank()) {
            return options.apiKey();
        }
        if (envVar != null && !envVar.isBlank()) {
            var env = System.getenv(envVar);
            if (env != null && !env.isBlank()) {
                return env;
            }
        }
        throw new IllegalStateException(
            "No API key. Set " + envVar + " or pass apiKey.");
    }
}
