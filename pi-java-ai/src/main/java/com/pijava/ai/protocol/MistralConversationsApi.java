package com.pijava.ai.protocol;

import java.util.HashMap;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.SubmissionPublisher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.pijava.ai.api.ApiOptions;
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
import com.pijava.ai.utils.SanitizeUnicode;

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
    }

    // ── Internals ─────────────────────────────────────────────

    @Override
    @SuppressWarnings("unchecked") // SSE response JSON parsing with generic Map types
    protected void streamInternal(StreamRequest request,
                                   SubmissionPublisher<StreamEvent> publisher) {
        var builder = new StreamPartialBuilder();
        try {
            String jsonBody = buildRequestBody(request);
            // B233（pi mc.ts:355-372）：基底头 → model.headers → options.headers
            // （null 删除）→ 缓存门自动补 x-affinity。
            var headers = MistralHeaders.build(apiKey,
                    request.model() == null ? Map.of() : request.model().headers(),
                    optionHeaders, sessionId, useCaching());

            publisher.submit(builder.emitStart());

            Iterator<PiHttpClient.ServerSentEvent> sseEvents =
                    http.postSse(baseUrl + "/chat/completions", jsonBody, headers, null,
                        headerTimeout);

            var toolCallBuilders = new HashMap<String, ToolCallBuilder>();
            // B80：pi :583 的 currentBlock（NONE/TEXT/THINKING），换型先关旧块再开新块。
            var contentState = new ContentState();
            var stop = new StopState();

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

    /**
     * pi {@code mistral-conversations.ts:583} 的 {@code currentBlock}：当前开着的内容块
     * 只有 NONE/TEXT/THINKING 三态（B80 前只有一个 boolean 文本槽）。
     *
     * <p>异型到达（text→thinking 或反向；GLM 还会在<b>同一个事件</b>里切，
     * {@code [thinking(…), {text:…}]}）先 {@link #finish} 旧块（发 {@code *_end}）
     * 再发新块的 {@code *_start} —— 对应 pi :652-655/:674-678/:693-697；
     * 工具调用到达即关块（pi :712-715）；流末统一收尾（pi :759）。</p>
     */
    private static final class ContentState {

        private enum Open { NONE, TEXT, THINKING }

        private Open current = Open.NONE;

        /** 切到目标块型：同型空转；异型先关旧块再开新块（pi 的 finish＋push start）。 */
        void switchTo(Open target, StreamPartialBuilder builder,
                      SubmissionPublisher<StreamEvent> publisher) {
            if (current == target) {
                return;
            }
            finish(builder, publisher);
            current = target;
            switch (target) {
                case TEXT -> publisher.submit(builder.emitTextStart());
                case THINKING -> publisher.submit(builder.emitThinkingStart());
                case NONE -> { }
            }
        }

        /** pi {@code :588-607} 的 finishCurrentBlock：text→text_end，thinking→thinking_end。 */
        void finish(StreamPartialBuilder builder,
                    SubmissionPublisher<StreamEvent> publisher) {
            switch (current) {
                case TEXT -> publisher.submit(builder.emitTextEnd());
                case THINKING -> publisher.submit(builder.emitThinkingEnd());
                case NONE -> { }
            }
            current = Open.NONE;
        }
    }

    /** pi 的 {@code output.stopReason} + {@code output.errorMessage} 两件套。 */
    private static final class StopState {
        // 初值 "pending"（mistral-conversations.ts:222）：收尾的「没观测到 finish reason」哨兵。
        private StopReason reason = StopReason.PENDING;
        private String errorMessage;
    }

    /**
     * 线格取值 → pi 的 {@code StopReason}（pi {@code mistral-conversations.ts:926-941}）。
     *
     * <p>⚠️ 与 Google 车道的**形状差异**：Mistral 把文案**做进映射**（{@code error} 与
     * {@code default} 两支都自带 {@code Provider stopped with: …}），而 Google 的映射只返回
     * 裸 {@link StopReason#ERROR}、文案由收尾处用 {@code rawStopReason} 拼。两处都照 pi 写，
     * 别「统一」。</p>
     *
     * <p>⚠️ 与 completions / Google 又不同的一处：本车道的未知取值 pi **不抛**（落 error 事件），
     * 与 completions 同向、与 Anthropic / Google 反向。</p>
     */
    private static MappedStopReason mapChatStopReason(String reason) {
        if (reason == null) {
            // pi 的 `if (reason === null) return {stopReason:"stop"}`（`:927`）。调用点的
            // 真值守卫已挡掉 null 与空串 ⇒ 流路径上不可达，留着是为了与 pi 逐行同形。
            return new MappedStopReason(StopReason.STOP, null);
        }
        return switch (reason) {
            case "stop" -> new MappedStopReason(StopReason.STOP, null);
            case "length", "model_length" -> new MappedStopReason(StopReason.LENGTH, null);
            case "tool_calls" -> new MappedStopReason(StopReason.TOOL_USE, null);
            // pi mistral-conversations.ts:948（7fb59f995）：补 " (server error)" 让 retry
            // 白名单的 server.?error 抓手生效 —— Mistral 以此报告瞬态服务失败，应可重试。
            case "error" -> new MappedStopReason(StopReason.ERROR,
                "Provider stopped with: error (server error)");
            default -> new MappedStopReason(StopReason.ERROR, "Provider stopped with: " + reason);
        };
    }

    /** 映射结果：pi 的 {@code { stopReason, errorMessage? }}。 */
    private record MappedStopReason(StopReason reason, String errorMessage) {}

    @SuppressWarnings("unchecked") // SSE response JSON parsing with generic Map types
    private void processSseData(String data,
                                  SubmissionPublisher<StreamEvent> publisher,
                                  StreamPartialBuilder builder,
                                  Map<String, ToolCallBuilder> toolBuilders,
                                  ContentState contentState,
                                  StopState stop,
                                  ModelInfo model) {
        try {
            var json = MAPPER.readValue(data, Map.class);

            // Usage —— 位置照 pi：usage 应用（{@code :596-611}）在「choices 空帧早退」
            // （{@code :613} 的 `if (!choice) continue`）**之前**。两个后果：
            // ① Mistral 真会把 usage 放在终局 {@code choices: []} 帧上 —— 早退在前会
            //    整帧丢掉（修复前的形状，M5fix 探针实测恰红这一条）；
            // ② 同帧既有内容又有 usage 时，UsageInfo 排在本帧 delta **之前** —— 与 pi
            //    的 partial 可见性同向（pi 先写 output.usage，后 push 的 delta 带着它）。
            //    UsageInfo 本身是 pi-java 自有的投影（pi 无 usage 事件）。
            var usage = (Map<String, Object>) json.get("usage");
            if (usage != null) {
                publisher.submit(builder.emitUsage(MistralUsage.parse(usage, model)));
            }

            var choices = (List<Map<String, Object>>) json.get("choices");
            if (choices == null || choices.isEmpty()) return;

            var choice = choices.get(0);
            // delta 缺失不再整段早退：终帧「只有 finish_reason、没有 delta」是合法形状，
            // 原先那道 `if (delta == null) return;` 会把它的取值整块丢掉。
            var delta = (Map<String, Object>) choice.get("delta");
            if (delta != null) {
                // B80（pi :643-708）：delta.content 既可能是串也可能是块数组 ——
                // 串 / {type:"thinking"} / {type:"text"} 三支，未知 type 静默忽略。
                Object contentNode = delta.get("content");
                if (contentNode != null) {
                    // pi :644 的 typeof 归一：串→[串]；数组逐项；既非串也非数组（理论不可达）按空。
                    List<?> items = contentNode instanceof String s
                            ? List.of(s)
                            : contentNode instanceof List<?> l ? l : List.of();
                    for (Object item : items) {
                        switch (MistralContentDelta.ofItem(item)) {
                            case MistralContentDelta.Text(var raw) -> {
                                // pi :647-650：先净化再判空（GLM 在 thinking/工具调用周围发的
                                // 空串不许开块，否则 thinking 被切成多块、Mistral 重放会拒）。
                                var textDelta = SanitizeUnicode.surrogates(raw);
                                if (textDelta.isEmpty()) break;
                                contentState.switchTo(ContentState.Open.TEXT, builder, publisher);
                                publisher.submit(builder.emitTextDelta(textDelta));
                            }
                            case MistralContentDelta.Thinking(var raw) -> {
                                // pi :667-687：parts 已在 MistralContentDelta 拼接、过滤非空。
                                var thinkingDelta = SanitizeUnicode.surrogates(raw);
                                if (thinkingDelta.isEmpty()) break;
                                contentState.switchTo(
                                        ContentState.Open.THINKING, builder, publisher);
                                publisher.submit(builder.emitThinkingDelta(thinkingDelta));
                            }
                            case MistralContentDelta.Ignored ignored -> { }
                        }
                    }
                }

                // Tool call delta —— pi :712-715：**首个**工具调用到达即关当前文本/思考块
                // （关闭在 toolCall 循环体内，空列表不触发 ⇒ 不能无条件 finish）。
                var toolCalls = (List<Map<String, Object>>) delta.get("tool_calls");
                if (toolCalls != null && !toolCalls.isEmpty()) {
                    contentState.finish(builder, publisher);
                    for (var tc : toolCalls) {
                        var index = String.valueOf(tc.getOrDefault("index", "0"));
                        var tcId = (String) tc.get("id");
                        var function = (Map<String, Object>) tc.get("function");
                        if (function == null) continue;

                        var name = (String) function.get("name");
                        var args = (String) function.get("arguments");

                        var toolBuilder = toolBuilders.computeIfAbsent(index,
                                k -> new ToolCallBuilder());

                        if (tcId != null && name != null && !toolBuilder.isStarted()) {
                            toolBuilder.start(tcId, name);
                            // 包⑥：起点即带身份（上行的 null 判定已保证两者非空）。
                            publisher.submit(builder.emitToolCallStart(tcId, name));
                        }
                        if (args != null) {
                            toolBuilder.append(args);
                            publisher.submit(builder.emitToolCallDelta(
                                    toolBuilder.id(), args));
                        }
                    }
                }
            }

            // Finish reason —— 放在 delta 处理**之后**（pi 是之前，`mistral-conversations.ts:613`）：
            // 下面那步「toolUse 补发 ToolCallEnd」要看工具块是否已完整，而工具块是上面刚折进来
            // 的 ⇒ 顺序反了会把同帧的 tool_call 终帧漏掉。**刻意偏差**，只此一处。
            var reason = (String) choice.get("finish_reason");
            if (reason != null && !reason.isEmpty()) {
                // pi `:614`：原值先落消息（⑨/D5），映射结果再落 stop.reason。
                builder.noteRawStopReason(reason);
                var mapped = mapChatStopReason(reason);
                stop.reason = mapped.reason();
                stop.errorMessage = mapped.errorMessage();
                if (stop.reason == StopReason.TOOL_USE) {
                    for (var tb : toolBuilders.values()) {
                        if (tb.isComplete()) {
                            publisher.submit(builder.emitToolCallEnd(
                                    tb.id(), tb.name()));
                        }
                    }
                }
            }

        } catch (JsonProcessingException e) {
            // Skip unparseable data lines
        }
    }

    private String buildRequestBody(StreamRequest request) throws JsonProcessingException {
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

        return MAPPER.writeValueAsString(body);
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
