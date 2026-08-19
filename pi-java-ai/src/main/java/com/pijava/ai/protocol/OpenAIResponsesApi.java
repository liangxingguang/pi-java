package com.pijava.ai.protocol;

import java.util.concurrent.SubmissionPublisher;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
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

    private final OpenAIClient client;
    private final ResponsesOptions responsesOptions;

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
        this.client = OpenAIOkHttpClient.builder()
            .apiKey(apiKey).baseUrl(baseUrl).build();
        this.responsesOptions = ResponsesOptions.from(options);
    }

    @Override
    protected void streamInternal(StreamRequest request,
                                  SubmissionPublisher<StreamEvent> publisher) {
        var params = ResponsesMessageConverter.buildParams(request, responsesOptions);
        try (var stream = client.responses().createStreaming(params)) {
            ResponsesStreamProcessor.process(stream, publisher);
        }
    }
}
