package com.pijava.ai.protocol;

import java.util.List;
import java.util.Comparator;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.Embedding;
import com.openai.models.embeddings.EmbeddingCreateParams;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.EmbeddingApi;
import com.pijava.ai.api.EmbeddingRequest;
import com.pijava.ai.api.EmbeddingResult;

/**
 * OpenAI 文本嵌入适配器（P6-28）—— pi-java 独有（pi 无 embedding provider）。
 * 走 openai-java SDK {@code client.embeddings()}。
 */
public final class OpenAIEmbeddingApi implements EmbeddingApi {

    private final OpenAIClient client;
    private final String apiKey;

    /** @param options       API options（apiKey 或 {@code OPENAI_API_KEY}）
     *  @param apiKeyEnvVar  环境变量名（通常 "OPENAI_API_KEY"） */
    public OpenAIEmbeddingApi(ApiOptions options, String apiKeyEnvVar) {
        this.apiKey = resolveApiKey(options, apiKeyEnvVar);
        var baseUrl = options.baseUrl() != null && !options.baseUrl().isBlank()
            ? options.baseUrl() : "https://api.openai.com/v1";
        this.client = OpenAIOkHttpClient.builder()
            .apiKey(apiKey).baseUrl(baseUrl).build();
    }

    @Override
    public EmbeddingResult embed(EmbeddingRequest request, ApiOptions options) {
        var response = client.embeddings().create(buildParams(request));
        var vectors = response.data().stream()
            .sorted(Comparator.comparingLong(Embedding::index))
            .map(e -> toFloatArray(e.embedding()))
            .toList();
        return new EmbeddingResult(request.model().modelName(), vectors,
            (int) response.usage().promptTokens());
    }

    /** 构建嵌入请求参数（包私有供测试）。 */
    static EmbeddingCreateParams buildParams(EmbeddingRequest request) {
        return EmbeddingCreateParams.builder()
            .model(request.model().modelName())
            .input(EmbeddingCreateParams.Input.ofArrayOfStrings(request.input()))
            .build();
    }

    static float[] toFloatArray(List<Float> values) {
        var out = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            out[i] = values.get(i);
        }
        return out;
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
