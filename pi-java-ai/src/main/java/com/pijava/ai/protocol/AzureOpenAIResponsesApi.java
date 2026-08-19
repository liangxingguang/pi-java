package com.pijava.ai.protocol;

import java.util.Map;
import java.util.concurrent.SubmissionPublisher;

import com.openai.azure.AzureOpenAIServiceVersion;
import com.openai.azure.AzureUrlPathMode;
import com.openai.azure.credential.AzureApiKeyCredential;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.stream.StreamEvent;

/**
 * Azure OpenAI Responses 适配器。
 *
 * <p>复用 {@link ResponsesMessageConverter} / {@link ResponsesStreamProcessor}，
 * 差异全在客户端构造与端点解析。基于 openai-java SDK 4.42.0 的原生 Azure 支持
 * （{@code AzureApiKeyCredential} + {@code AzureUrlPathMode.AUTO}），主机后缀识别与
 * {@code api-version}／部署路径注入由 SDK 处理，此处不重复实现。环境变量取 pi 的
 * {@code AZURE_OPENAI_API_KEY}（key 名由 {@code apiKeyEnvVar} 控制），显式传
 * {@code credential(...)} 以绕开 SDK {@code fromEnv()} 的 {@code AZURE_OPENAI_KEY}
 * 冲突。</p>
 */
public final class AzureOpenAIResponsesApi extends AbstractChatApi {

    private final OpenAIClient client;
    private final ResponsesOptions responsesOptions;
    private final AzureOptions azureOptions;

    /**
     * Create an adapter, resolving the API key from an env var.
     *
     * @param options     API options (apiKey or env var required)
     * @param apiKeyEnvVar the environment variable holding the API key
     */
    public AzureOpenAIResponsesApi(ApiOptions options, String apiKeyEnvVar) {
        this.azureOptions = AzureOptions.from(options);
        String apiKey = resolveApiKey(options, apiKeyEnvVar);
        String baseUrl = resolveBaseUrl(options);
        String apiVersion = resolveApiVersion();
        this.client = OpenAIOkHttpClient.builder()
            .baseUrl(baseUrl)
            .credential(AzureApiKeyCredential.create(apiKey))
            .azureServiceVersion(AzureOpenAIServiceVersion.fromString(apiVersion))
            .azureUrlPathMode(AzureUrlPathMode.AUTO)
            .build();
        this.responsesOptions = ResponsesOptions.from(options);
    }

    @Override
    protected void streamInternal(StreamRequest request,
                                  SubmissionPublisher<StreamEvent> publisher) {
        String deploymentName = resolveDeploymentName(request);
        var params = ResponsesMessageConverter.buildParams(
            request, responsesOptions, deploymentName);
        try (var stream = client.responses().createStreaming(params)) {
            ResponsesStreamProcessor.process(stream, publisher);
        }
    }

    // ── 配置解析 ─────────────────────────────────────────────────────────

    /** 部署名：azureDeploymentName → DEPLOYMENT_NAME_MAP.get(model.id) → model.id。 */
    private String resolveDeploymentName(StreamRequest request) {
        if (!isBlank(azureOptions.deploymentName())) {
            return azureOptions.deploymentName();
        }
        Map<String, String> map = AzureOptions.parseDeploymentNameMap(
            System.getenv("AZURE_OPENAI_DEPLOYMENT_NAME_MAP"));
        String modelId = request.model().modelName();
        return map.getOrDefault(modelId, modelId);
    }

    /**
     * baseUrl 五级解析（对齐 pi {@code resolveAzureConfig}）：
     * 1. azureBaseUrl option → 2. AZURE_OPENAI_BASE_URL env →
     * 3. resourceName 拼默认 → 4. ApiOptions.baseUrl → 5. 全空抛异常。
     * 归一化只做去尾部斜杠 + URL 合法性校验，路径补全交给 SDK。
     */
    private String resolveBaseUrl(ApiOptions options) {
        String baseUrl = azureOptions.baseUrl();
        if (isBlank(baseUrl)) {
            baseUrl = System.getenv("AZURE_OPENAI_BASE_URL");
        }
        if (isBlank(baseUrl) && !isBlank(azureOptions.resourceName())) {
            baseUrl = "https://" + azureOptions.resourceName()
                + ".openai.azure.com/openai/v1";
        }
        if (isBlank(baseUrl) && !isBlank(options.baseUrl())) {
            baseUrl = options.baseUrl();
        }
        if (isBlank(baseUrl)) {
            throw new IllegalStateException(
                "Azure OpenAI base URL is required. Set AZURE_OPENAI_BASE_URL or "
                + "AZURE_OPENAI_RESOURCE_NAME, or pass azureBaseUrl, azureResourceName, "
                + "or baseUrl.");
        }
        return normalizeBaseUrl(baseUrl);
    }

    /** apiVersion：azureApiVersion option → AZURE_OPENAI_API_VERSION env → "v1"。 */
    private String resolveApiVersion() {
        if (!isBlank(azureOptions.apiVersion())) {
            return azureOptions.apiVersion();
        }
        String env = System.getenv("AZURE_OPENAI_API_VERSION");
        if (!isBlank(env)) {
            return env;
        }
        return AzureOptions.DEFAULT_API_VERSION;
    }

    private static String normalizeBaseUrl(String baseUrl) {
        String trimmed = baseUrl.trim().replaceAll("/+$", "");
        try {
            new java.net.URI(trimmed);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "Invalid Azure OpenAI base URL: " + baseUrl, e);
        }
        return trimmed;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
