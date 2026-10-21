package com.pijava.ai.protocol;

import java.util.Map;
import java.util.concurrent.SubmissionPublisher;

import com.openai.azure.AzureOpenAIServiceVersion;
import com.openai.azure.AzureUrlPathMode;
import com.openai.azure.credential.AzureApiKeyCredential;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.responses.ResponseCreateParams;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.GrammarInputProperties;
import com.pijava.ai.api.ProviderHooks;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.CompatResolver;
import com.pijava.ai.http.ProviderRetry;
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

    @Override
    public String apiName() {
        return "azure-openai-responses";
    }

    private final OpenAIClient client;
    private final ResponsesOptions responsesOptions;
    private final AzureOptions azureOptions;

    /** A-14：初始请求获取的重试选项。 */
    private final ProviderRetry.Options providerRetry;

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
        var clientBuilder = OpenAIOkHttpClient.builder()
            .baseUrl(baseUrl)
            .credential(AzureApiKeyCredential.create(apiKey))
            .azureServiceVersion(AzureOpenAIServiceVersion.fromString(apiVersion))
            .azureUrlPathMode(AzureUrlPathMode.AUTO)
            // A-14（G1）：SDK 内置重试关到 0，初始请求由 ProviderRetry 独占。
            .maxRetries(0);
        // docs/17（裁决⑦）：extra["headers"] 应用补挂——pi azure-openai-responses.ts:156-169
        // 是 {User-Agent, ...model.headers, ...options.headers} 全量进 defaultHeaders；
        // 本车道此前从不读（存量缺口，头事件/models.json 头对本车道曾为空操作）。
        putExtraHeaders(options, clientBuilder::putHeader);
        this.client = clientBuilder.build();
        this.providerRetry = ProviderRetry.optionsOf(options);
        this.responsesOptions = ResponsesOptions.from(options);
        // docs/17（B248）：pi 请求面三钩（azure-openai-responses.ts:77/94/98）。
        this.payloadHook = ProviderHooks.payloadHookOf(options);
        this.responseHook = ProviderHooks.responseHookOf(options);
        this.streamEventHook = ProviderHooks.streamEventHookOf(options);
    }

    /** docs/17（B248）：pi {@code onPayload}（azure-openai-responses.ts:77）——null ≙ undefined。 */
    private final ProviderHooks.PayloadHook payloadHook;

    /** docs/17（B248）：pi {@code onResponse}（:94）——null ≙ undefined。 */
    private final ProviderHooks.ResponseHook responseHook;

    /** docs/17（B248）：pi {@code onProviderStreamEvent}（:98）——null ≙ undefined。 */
    private final ProviderHooks.StreamEventHook streamEventHook;

    @Override
    protected void streamInternal(StreamRequest request,
                                  SubmissionPublisher<StreamEvent> publisher) {
        String deploymentName = resolveDeploymentName(request);
        // pi azure-openai-responses.ts:296 / :319：`supportsStrictMode: model.compat?.supportsStrictMode ?? true`
        // —— 与 openai-responses 车道（`?? false`）**缺省相反**，是 pi 的事实，别改成一致。
        var compat = CompatResolver.forResponses(request.model(), true);
        // 原 docs/69（pi azure :108-111）：grammar 能力表请求起点一次算出。
        var grammarProperties = GrammarInputProperties.create(
            com.pijava.ai.api.Transcripts.getDeclaredTools(
                com.pijava.ai.api.Transcripts.resolveTranscript(
                    request.transcript(), compat).messages()),
            Boolean.TRUE.equals(compat.supportsOpenAIGrammarTools()));
        var params = ResponsesMessageConverter.buildParams(
            request, responsesOptions, deploymentName, apiName(),
            // B240：pi azure-openai-responses.ts 无 isChatGPTSignIn/omit 门（零命中），恒 false。
            compat, grammarProperties, false, Map.of());
        // docs/17（pi :77-80 逐字）：载荷构建后、HTTP 前；非 null 返回 ⇒ 替换。
        if (payloadHook != null) {
            var next = payloadHook.onPayload(params, request.model());
            if (next != null) {
                params = ProviderHooks.requirePayloadType(next, ResponseCreateParams.class);
            }
        }
        // A-14（R7）：只包初始请求获取。获取动作放进 processor 的车道 try 内
        // （supplier 里），获取期 HTTP 错误才会落 pi 的带前缀 catch（原 docs/48 §5.3）。
        // docs/17：withRawResponse 拿 status/headers，成功获取后、流消费前 fire onResponse。
        final var finalParams = params; // 替换臂后 lambda 捕获需要 effectively final
        ResponsesStreamProcessor.process(
            () -> {
                var rawResponse = ProviderRetry.retry(
                    () -> client.withRawResponse().responses().createStreaming(finalParams),
                    ProviderRetry::ofOpenAi, providerRetry);
                if (responseHook != null) {
                    responseHook.onResponse(rawResponse.statusCode(),
                        ProviderHooks.flattenHeaders(rawResponse.headers().names(),
                            rawResponse.headers()::values),
                        request.model());
                }
                return rawResponse.parse();
            },
            publisher, request.model(), grammarProperties,
            // pi azure-openai-responses.ts:52-55：常量前缀，无 ChatGPT 链接。
            e -> com.pijava.ai.utils.ProviderErrors.format(
                com.pijava.ai.utils.ProviderErrors.fromOpenAi(e),
                "Azure OpenAI API error"),
            streamEventHook);
    }

    // ── 配置解析 ─────────────────────────────────────────────────────────

    /** 部署名：azureDeploymentName → DEPLOYMENT_NAME_MAP.get(model.id) → model.id。 */
    private String resolveDeploymentName(StreamRequest request) {
        if (!isBlank(azureOptions.deploymentName())) {
            return azureOptions.deploymentName();
        }
        Map<String, String> map = AzureOptions.parseDeploymentNameMap(
            System.getenv("AZURE_OPENAI_DEPLOYMENT_NAME_MAP"));
        String modelId = request.modelId().modelName();
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
