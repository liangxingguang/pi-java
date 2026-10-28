package com.pijava.ai.protocol;

import java.util.HashMap;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.SubmissionPublisher;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.ProviderHooks;
import com.pijava.ai.api.SimpleOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.api.Transcripts;
import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.catalog.CacheRetention;
import com.pijava.ai.catalog.CompatResolver;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.http.PiHttpClient;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.stream.StreamPartialBuilder;
import com.pijava.ai.stream.ToolCallBuilder;
import com.pijava.ai.utils.ProviderErrors;

/**
 * Mistral Chat Completions API adapter using raw HTTP + SSE.
 *
 * <p>Phase 2a: emits the full 13-event protocol with {@code partial} snapshots
 * via {@link StreamPartialBuilder}. Mistral has no official Java SDK; uses
 * {@link PiHttpClient} for JSON requests and SSE parsing.</p>
 *
 * <h3>B20：stop reason 从线格读、收尾按 pi 严格判定（原 docs/31 §8.35.14）</h3>
 *
 * <p>pi 的收尾（{@code mistral-conversations.ts:150-161}）是四段判：abort →
 * {@code pending} → {@code error} → done。本车道此前**一段都没有**，而且读点位置也是错的：
 * 取值在 {@code processSseData} 里读，却排在那道 {@code if (delta == null) return} **之后**
 * ——「只有 finish_reason、没有 delta」的终帧取值被整块丢掉；读到的取值又只翻
 * {@code tool_calls}→{@code tool_use} 一种，其余原样发出去（{@code model_length} 发成
 * {@code "model_length"}、{@code error} 发成 {@code done("error")}），局部变量还兜底成
 * {@code "stop"} 把「什么都没看到」伪装成「正常收尾」。</p>
 *
 * <p>两处与 pi 的取舍已写在各读点旁，汇总：</p>
 * <ul>
 *   <li><b>abort 检查（pi {@code :150}）不可达</b> —— {@code StreamRequest} 没有 signal，
 *       中止由宿主 {@code PiLoopRunner.markAborted} 在流外处理（§8.35.14 第三节 ③）。</li>
 *   <li><b>读点排在 delta 处理之后</b>（pi 是之前）—— 只此一处刻意偏差，理由见读点注释；
 *       pi 的 {@code rawStopReason}（{@code :614}）随第 ⑨ 包（D5）落地，写在同一个读点旁。</li>
 * </ul>
 */
public final class MistralConversationsApi extends AbstractChatApi {

    @Override
    public String apiName() {
        return "mistral-conversations";
    }

    private static final String DEFAULT_BASE_URL = "https://api.mistral.ai/v1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * B229（pi {@code 6b07b4e57}）：超时只盖等响应头。生产值取 pi coding-agent 的
     * {@code DEFAULT_HTTP_IDLE_TIMEOUT_MS = 300_000}（{@code http-dispatcher.ts:4}，
     * sdk.ts:350 链恒给该值）；ai 包 standalone 的 60s 默认 bin pi 不可达。
     */
    private static final Duration DEFAULT_HEADER_TIMEOUT = Duration.ofSeconds(300);

    /** pi 三源合并的缓存保留期环境变量（与 completions/anthropic 车道同一个，{@code :342} 共用）。 */
    private static final String CACHE_RETENTION_ENV = "PI_CACHE_RETENTION";

    private final PiHttpClient http;
    private final String apiKey;
    private final String baseUrl;
    private final Duration headerTimeout;
    /**
     * B233：原始 sessionId —— {@code x-affinity} 头（mc.ts:368）与
     * {@code prompt_cache_key} 体键（:546）共用；cacheRetention=none 门在 {@link #useCaching}。
     */
    private final String sessionId;
    /** B233：extra["cacheRetention"]；缺席 {@code Optional.empty()} ≙ pi undefined（别塞默认值）。 */
    private final Optional<CacheRetention> cacheRetention;
    /** B233：extra["headers"] 原始 map（值可能为 null ⇒ 删除，pi :374-380）；缺席为 null。 */
    private final Map<?, ?> optionHeaders;

    /**
     * Create an adapter for the given options.
     *
     * @param options API options (apiKey or {@code MISTRAL_API_KEY} required)
     */
    public MistralConversationsApi(ApiOptions options) {
        this(options, DEFAULT_HEADER_TIMEOUT);
    }

    /**
     * docs/52 测试接缝：自定义头超时（生产恒走 {@link #DEFAULT_HEADER_TIMEOUT}）。
     */
    MistralConversationsApi(ApiOptions options, Duration headerTimeout) {
        this.apiKey = resolveApiKey(options);
        this.baseUrl = options.baseUrl() != null && !options.baseUrl().isBlank()
                ? options.baseUrl() : DEFAULT_BASE_URL;
        this.headerTimeout = headerTimeout;
        // B233：宿主 DefaultProviders.requestExtra 已对所有 provider 注入 sessionId/
        // cacheRetention（适配器每请求新建，不串味）；per-request headers 走 extra["headers"]。
        this.sessionId = sessionIdOf(options);
        this.cacheRetention = retentionOf(options);
        Object rawHeaders = options.extra() == null ? null : options.extra().get("headers");
        this.optionHeaders = rawHeaders instanceof Map<?, ?> map ? map : null;
        // A-14（G3）：重试次数取选项（默认 0 ⇒ 不重试），cap 经 extra 过桥。
        var policyBuilder = new com.pijava.ai.http.RetryPolicy.Builder()
                .maxRetries(options.maxRetries());
        if (options.extra() != null
                && options.extra().get("maxRetryDelayMs") instanceof Number number) {
            policyBuilder.maxRetryDelayMs(Duration.ofMillis(number.longValue()));
        }
        this.http = PiHttpClient.builder()
                .userAgent("pi-java/dev")
                .retryPolicy(policyBuilder.build())
                .build();
        // docs/17（B248）：pi 请求面三钩（mistral-conversations.ts:148/330/611）。
        this.payloadHook = ProviderHooks.payloadHookOf(options);
        this.responseHook = ProviderHooks.responseHookOf(options);
        this.streamEventHook = ProviderHooks.streamEventHookOf(options);
        this.sseProcessor = new MistralSseProcessor(streamEventHook);
    }

    /** docs/17（B248）：pi {@code onPayload}（mistral-conversations.ts:148）——null ≙ undefined。 */
    private final ProviderHooks.PayloadHook payloadHook;

    /** docs/17（B248）：pi {@code onResponse}（:330）——null ≙ undefined。 */
    private final ProviderHooks.ResponseHook responseHook;

    /** docs/17（B248）：pi {@code onProviderStreamEvent}（:611）——null ≙ undefined。 */
    private final ProviderHooks.StreamEventHook streamEventHook;

    /** SSE 帧处理簇（结构债拆分——实现逐字迁往 {@link MistralSseProcessor}）。 */
    private final MistralSseProcessor sseProcessor;

    // ── Internals ─────────────────────────────────────────────

    @Override
    @SuppressWarnings("unchecked") // SSE response JSON parsing with generic Map types
    protected void streamInternal(StreamRequest request,
                                   SubmissionPublisher<StreamEvent> publisher) {
        var builder = new StreamPartialBuilder();
        try {
            // docs/17（pi mc.ts:147-152 逐字）：载荷＝构建出的 Map；非 null 返回 ⇒ 替换后再序列化。
            Map<String, Object> bodyMap = buildRequestBodyMap(request);
            if (payloadHook != null) {
                var next = payloadHook.onPayload(bodyMap, request.model());
                if (next != null) {
                    @SuppressWarnings("unchecked") // 同型守卫已过；泛型擦除下 Map 即本体
                    var replaced = (Map<String, Object>) ProviderHooks.requirePayloadType(
                        next, Map.class);
                    bodyMap = replaced;
                }
            }
            String jsonBody = MAPPER.writeValueAsString(bodyMap);
            // B233（pi mc.ts:355-372）：基底头 → model.headers → options.headers
            // （null 删除）→ 缓存门自动补 x-affinity。
            var headers = MistralHeaders.build(apiKey,
                    request.model() == null ? Map.of() : request.model().headers(),
                    optionHeaders, sessionId, useCaching());

            publisher.submit(builder.emitStart());

            // docs/17（pi mc.ts:330）：终态响应观察（含错误状态——pi fetch 车道在
            // !ok 判定前 fire）；无钩 ⇒ null observer（零开销旧路径）。
            var responseObserver = responseHook == null ? null
                : (com.pijava.ai.http.SseResponseObserver) (status, responseHeaders) ->
                    responseHook.onResponse(status,
                        ProviderHooks.flattenHeaders(responseHeaders.map()), request.model());
            Iterator<PiHttpClient.ServerSentEvent> sseEvents =
                    http.postSse(baseUrl + "/chat/completions", jsonBody, headers, null,
                        headerTimeout, responseObserver);

            var toolCallBuilders = new HashMap<String, ToolCallBuilder>();
            // B80：pi :583 的 currentBlock（NONE/TEXT/THINKING），换型先关旧块再开新块。
            var contentState = new MistralSseProcessor.ContentState();
            var stop = new MistralSseProcessor.StopState();

            while (sseEvents.hasNext()) {
                var sse = sseEvents.next();
                if ("[DONE]".equals(sse.data())) {
                    // pi 的解析器把 `[DONE]` 当作**迭代结束**（mistral-conversations.ts:494
                    // 的哨兵 + `:458` 的 `return`）⇒ 收尾判定在循环**之外**统一跑。
                    // 此前这里提前 `emitDone` + `return`，整段收尾判定被跳过。
                    break;
                }
                processSseData(sse.data(), publisher, builder,
                        toolCallBuilders, contentState, stop, request.model());
            }
            // pi :759 的 finishCurrentBlock(currentBlock)（此前只有单一文本槽）。
            contentState.finish(builder, publisher);
            // ⚠️ pi 的 abort 检查（:150）在车道层**结构上不可达** —— `StreamRequest` 没有
            // signal，中止由宿主 `PiLoopRunner.markAborted` 在流外处理（§8.35.14 第三节 ③）。
            if (stop.reason == StopReason.PENDING) {
                // 严格收尾（pi :154-155）：一个 finish reason 都没观测到 ⇒ 绝不当成正常结束。
                throw new IllegalStateException("Mistral stream ended without a finish reason");
            }
            // pi :157 还比了 `"aborted"`，那个值只由上面的 abort 检查写入 ⇒ 不可达。
            if (stop.reason == StopReason.ERROR) {
                // pi :158：文案取 `errorMessage`（**映射里**给的），缺失才兜底。
                throw new IllegalStateException(stop.errorMessage != null
                        ? stop.errorMessage : "An unknown error occurred");
            }
            publisher.submit(builder.emitDone(stop.reason));
        } catch (Exception e) {
            // pi 的 catch（:166-171）只发一条 error 就结束 ⇒ 一条流**只有一个**终局事件。
            // B229（pi mistral-conversations.ts:321-324）：等响应头超时的文案逐字。
            if (e instanceof com.pijava.ai.http.HeaderTimeoutException hte) {
                publisher.submit(builder.emitError(StopReason.ERROR, new IllegalStateException(
                    "Mistral response headers timed out after "
                        + hte.timeout().toMillis() + "ms", e)));
                return;
            }
            // 只有 HTTP 错误（pi 的 MistralHttpError ≙ statusCode>0 的 PiHttpException）
            // 改文案；其余错误原样发，保留异常身份（RetryDelayTooLongException 等要被
            // 上游按类型识别，且 pi 的 formatMistralError 对它们也是 message 透传）。
            boolean httpError =
                e instanceof com.pijava.ai.http.PiHttpException http && http.statusCode() > 0;
            publisher.submit(builder.emitError(StopReason.ERROR, httpError
                ? new IllegalStateException(ProviderErrors.formatMistralError(e), e) : e));
        }
    }

    /** SSE 帧处理（实现逐字迁往 {@link MistralSseProcessor}，结构债拆分）。 */
    private void processSseData(String data,
                                  SubmissionPublisher<StreamEvent> publisher,
                                  StreamPartialBuilder builder,
                                  Map<String, ToolCallBuilder> toolBuilders,
                                  MistralSseProcessor.ContentState contentState,
                                  MistralSseProcessor.StopState stop,
                                  ModelInfo model) {
        sseProcessor.processSseData(data, publisher, builder, toolBuilders,
                contentState, stop, model);
    }

    /**
     * docs/17：序列化前移到调用点（{@code onPayload} 钩要拿 Map 本体）——
     * 本方法只构建，返回 Map。
     */
    private Map<String, Object> buildRequestBodyMap(StreamRequest request) {
        var body = new HashMap<String, Object>();
        body.put("model", request.modelId().modelName());
        body.put("stream", true);
        // 包 A7：mistral 车道在 pi 里**没有** getCompat（直接读 partial，`mistral-conversations.ts:130`）
        // ⇒ 这里补成显式解析，形状与另外三条车道一致（原 docs/53 §4.1）。
        var transcript = Transcripts.resolveTranscript(request.transcript(),
            CompatResolver.forMistral(request.model()));
        body.put("messages", MistralMessages.toWire(transcript, request, apiName()));

        var tools = Transcripts.getCurrentTools(transcript.messages());
        if (!tools.isEmpty()) {
            body.put("tools", toMistralTools(tools));
        }
        if (request.maxTokens() > 0) {
            body.put("max_tokens", request.maxTokens());
        }
        if (request.temperature() >= 0) {
            body.put("temperature", request.temperature());
        }

        // 包 B155：pi `mistral-conversations.ts:200-208` 的 streamSimple → `:525-526` 的写点。
        // 两处都是**真值判断** ⇒ 空串/缺席不发；两个 helper 互斥。
        var clamped = SimpleOptions.clampedReasoningEffort(request.model(), request.reasoning());
        var shouldUseReasoning = request.model().capabilities().contains(ModelCapability.THINKING)
            && clamped.isPresent();
        if (shouldUseReasoning && MistralThinking.usesPromptModeReasoning(request.model())) {
            body.put("promptMode", "reasoning");
        }
        if (shouldUseReasoning && MistralThinking.usesReasoningEffort(request.model())) {
            body.put("reasoningEffort", MistralThinking.effort(request.model(), clamped.get()));
        }

        // B233（pi mc.ts:546 ＋ toMistralWirePayload :399）：开缓存时体键发**原始** sessionId
        // —— 注意不夹 64 码点（clamp 是 OpenAI 系 clampOpenAIPromptCacheKey 的事）。
        if (useCaching()) {
            body.put("prompt_cache_key", sessionId);
        }

        return body;
    }

    /**
     * pi {@code shouldUsePromptCaching}（mc.ts:551-553）：retention 三源合并（选项 ??
     * {@code PI_CACHE_RETENTION} ?? short）后≠{@code none}，且 sessionId 真值。
     * 与 completions 车道同一个 {@link CompatResolver#resolveCacheRetention} 口。
     */
    private boolean useCaching() {
        return CompatResolver.resolveCacheRetention(
                    cacheRetention, System.getenv(CACHE_RETENTION_ENV)) != CacheRetention.NONE
                && sessionId != null && !sessionId.isBlank();
    }

    /** 从 extra 读 sessionId（同 OpenAICompletionsApi.sessionIdOf）；blank ⇒ null。 */
    private static String sessionIdOf(ApiOptions options) {
        Map<String, Object> extra = options.extra();
        Object raw = extra == null ? null : extra.get("sessionId");
        return raw == null || raw.toString().isBlank() ? null : raw.toString();
    }

    /** 从 extra 读 cacheRetention（同 OpenAICompletionsApi.retentionOf）；非法串落缺席。 */
    private static Optional<CacheRetention> retentionOf(ApiOptions options) {
        Map<String, Object> extra = options.extra();
        Object raw = extra == null ? null : extra.get("cacheRetention");
        if (raw instanceof CacheRetention retention) {
            return Optional.of(retention);
        }
        return CacheRetention.parse(raw == null ? null : raw.toString());
    }

    private List<Map<String, Object>> toMistralTools(List<ToolDefinition> definitions) {
        return MistralTools.toTools(definitions);
    }

    private static String resolveApiKey(ApiOptions options) {
        if (options.apiKey() != null && !options.apiKey().isBlank()) {
            return options.apiKey();
        }
        var env = System.getenv("MISTRAL_API_KEY");
        if (env != null && !env.isBlank()) {
            return env;
        }
        throw new IllegalStateException(
                "No Mistral API key found. Set MISTRAL_API_KEY or pass apiKey in ApiOptions.");
    }
}
