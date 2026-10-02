package com.pijava.ai.protocol;

import java.util.concurrent.SubmissionPublisher;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.CompatResolver;
import com.pijava.ai.http.ProviderRetry;
import com.pijava.ai.stream.StreamEvent;

/**
 * OpenAI Responses 协议适配器（对齐 pi {@code openai} provider 的
 * {@code openai-responses} 协议）。
 *
 * <p>Responses API 是 OpenAI 面向 Agent 场景的新标准：单独的 reasoning 内容通道、
 * 服务端 {@code previous_response_id} 会话亲和、原生 prompt cache 控制。相比
 * Chat Completions 的关键差异集中在请求构建与事件映射，均由
 * {@link ResponsesMessageConverter} / {@link ResponsesStreamProcessor} 承担。</p>
 */
public final class OpenAIResponsesApi extends AbstractChatApi {

    @Override
    public String apiName() {
        return "openai-responses";
    }

    private final OpenAIClient client;
    private final ResponsesOptions responsesOptions;

    /** A-14：初始请求获取的重试选项。 */
    private final ProviderRetry.Options providerRetry;

    /** Create an adapter for the given options (key from {@code OPENAI_API_KEY}). */
    public OpenAIResponsesApi(ApiOptions options) {
        this(options, "OPENAI_API_KEY");
    }

    /**
     * Create an adapter, resolving the API key from an env var.
     *
     * @param options     API options (apiKey or env var required)
     * @param apiKeyEnvVar the environment variable holding the API key
     */
    public OpenAIResponsesApi(ApiOptions options, String apiKeyEnvVar) {
        String apiKey = resolveApiKey(options, apiKeyEnvVar);
        String baseUrl = options.baseUrl() != null && !options.baseUrl().isBlank()
            ? options.baseUrl() : "https://api.openai.com/v1";
        // A-14（G1）：SDK 内置重试关到 0，初始请求由 ProviderRetry 独占。
        var clientBuilder = OpenAIOkHttpClient.builder()
            .apiKey(apiKey).baseUrl(baseUrl).maxRetries(0);
        // D-P1：models.json 合并来的 default headers（docs/65）。
        putExtraHeaders(options, clientBuilder::putHeader);
        this.client = clientBuilder.build();
        this.providerRetry = ProviderRetry.optionsOf(options);
        this.responsesOptions = ResponsesOptions.from(options);
        this.baseUrl = baseUrl;
    }

    /** 包 B103：解析后的 base URL（openrouter 探测用）。 */
    private final String baseUrl;

    @Override
    protected void streamInternal(StreamRequest request,
                                  SubmissionPublisher<StreamEvent> publisher) {
        // pi openai-responses.ts:74：`supportsStrictMode: model.compat?.supportsStrictMode ?? false`
        // —— 本车道的缺省是**不发** strict 键（azure 侧相反，见该车道）。
        // 包 A7：缺省由**这里**喂进解析器，转换器只消费（docs/53 §4.1）。
        var compat = CompatResolver.forResponses(request.model(), false);
        var params = ResponsesMessageConverter.buildParams(
            request, responsesOptions, request.modelId().modelName(), apiName(), compat,
            SessionAffinityHeaders.responses(responsesOptions.sessionId(),
                compat.sessionAffinityFormat(), request.modelId().provider(), baseUrl));
        // A-14（R7）：只包初始请求获取。
        var stream = ProviderRetry.retry(
            () -> client.responses().createStreaming(params),
            ProviderRetry::ofOpenAi, providerRetry);
        try (stream) {
            ResponsesStreamProcessor.process(stream, publisher, request.model());
        }
    }
}
