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
import com.pijava.ai.http.ProviderRetry;

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
        // A-14（G1）：SDK 内置重试关到 0，初始请求由 ProviderRetry 独占。
        this.client = OpenAIOkHttpClient.builder()
            .apiKey(apiKey).baseUrl(baseUrl).maxRetries(0).build();
        this.providerRetry = ProviderRetry.optionsOf(options);
    }

    /** A-14：构造期选项（调用期未再传 options 时使用）。 */
    private final ProviderRetry.Options providerRetry;

    @Override
    public EmbeddingResult embed(EmbeddingRequest request, ApiOptions options) {
        // A-14：非流式只包初始请求获取；调用期 options 优先于构造期。
        var retryOptions = options != null
            ? ProviderRetry.optionsOf(options) : providerRetry;
        var response = ProviderRetry.retry(
            () -> client.embeddings().create(buildParams(request)),
            ProviderRetry::ofOpenAi, retryOptions);
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
