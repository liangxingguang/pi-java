package com.pijava.ai.protocol;

import java.util.HashMap;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.SubmissionPublisher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.SimpleOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.api.Transcripts;
import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.catalog.CompatResolver;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.http.PiHttpClient;
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
     * pi 累加器的 stop reason 初值（{@code mistral-conversations.ts:222}），
     * 非 pi 词汇表取值；收尾拿它当「一个 finish reason 都没观测到」的哨兵。
     */
    private static final String PENDING = "pending";

    /**
     * B229（pi {@code 6b07b4e57}）：超时只盖等响应头。生产值取 pi coding-agent 的
     * {@code DEFAULT_HTTP_IDLE_TIMEOUT_MS = 300_000}（{@code http-dispatcher.ts:4}，
     * sdk.ts:350 链恒给该值）；ai 包 standalone 的 60s 默认 bin pi 不可达。
     */
    private static final Duration DEFAULT_HEADER_TIMEOUT = Duration.ofSeconds(300);

    private final PiHttpClient http;
    private final String apiKey;
    private final String baseUrl;
    private final Duration headerTimeout;

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
            var headers = Map.of(
                    "Authorization", "Bearer " + apiKey,
                    "Accept", "text/event-stream");

            publisher.submit(builder.emitStart());

            Iterator<PiHttpClient.ServerSentEvent> sseEvents =
                    http.postSse(baseUrl + "/chat/completions", jsonBody, headers, null,
                        headerTimeout);

            var toolCallBuilders = new HashMap<String, ToolCallBuilder>();
            var textStarted = new boolean[]{false};
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
                        toolCallBuilders, textStarted, stop, request.model());
            }
            if (textStarted[0]) publisher.submit(builder.emitTextEnd());
            // ⚠️ pi 的 abort 检查（:150）在车道层**结构上不可达** —— `StreamRequest` 没有
            // signal，中止由宿主 `PiLoopRunner.markAborted` 在流外处理（§8.35.14 第三节 ③）。
            if (PENDING.equals(stop.reason)) {
                // 严格收尾（pi :154-155）：一个 finish reason 都没观测到 ⇒ 绝不当成正常结束。
                throw new IllegalStateException("Mistral stream ended without a finish reason");
            }
            // pi :157 还比了 `"aborted"`，那个值只由上面的 abort 检查写入 ⇒ 不可达。
            if ("error".equals(stop.reason)) {
                // pi :158：文案取 `errorMessage`（**映射里**给的），缺失才兜底。
                throw new IllegalStateException(stop.errorMessage != null
                        ? stop.errorMessage : "An unknown error occurred");
            }
            publisher.submit(builder.emitDone(stop.reason));
        } catch (Exception e) {
            // pi 的 catch（:166-171）只发一条 error 就结束 ⇒ 一条流**只有一个**终局事件。
            // B229（pi mistral-conversations.ts:321-324）：等响应头超时的文案逐字。
            if (e instanceof com.pijava.ai.http.HeaderTimeoutException hte) {
                publisher.submit(builder.emitError("error", new IllegalStateException(
                    "Mistral response headers timed out after "
                        + hte.timeout().toMillis() + "ms", e)));
                return;
            }
            // 只有 HTTP 错误（pi 的 MistralHttpError ≙ statusCode>0 的 PiHttpException）
            // 改文案；其余错误原样发，保留异常身份（RetryDelayTooLongException 等要被
            // 上游按类型识别，且 pi 的 formatMistralError 对它们也是 message 透传）。
            boolean httpError =
                e instanceof com.pijava.ai.http.PiHttpException http && http.statusCode() > 0;
            publisher.submit(builder.emitError("error", httpError
                ? new IllegalStateException(ProviderErrors.formatMistralError(e), e) : e));
        }
    }

    /** pi 的 {@code output.stopReason} + {@code output.errorMessage} 两件套。 */
    private static final class StopState {
        private String reason = PENDING;
        private String errorMessage;
    }

    /**
     * 线格取值 → pi 的 {@code StopReason}（pi {@code mistral-conversations.ts:926-941}）。
     *
     * <p>⚠️ 与 Google 车道的**形状差异**：Mistral 把文案**做进映射**（{@code error} 与
     * {@code default} 两支都自带 {@code Provider stopped with: …}），而 Google 的映射只返回
     * 裸 {@code "error"}、文案由收尾处用 {@code rawStopReason} 拼。两处都照 pi 写，别「统一」。</p>
     *
     * <p>⚠️ 与 completions / Google 又不同的一处：本车道的未知取值 pi **不抛**（落 error 事件），
     * 与 completions 同向、与 Anthropic / Google 反向。</p>
     */
    private static MappedStopReason mapChatStopReason(String reason) {
        if (reason == null) {
            // pi 的 `if (reason === null) return {stopReason:"stop"}`（`:927`）。调用点的
            // 真值守卫已挡掉 null 与空串 ⇒ 流路径上不可达，留着是为了与 pi 逐行同形。
            return new MappedStopReason("stop", null);
        }
        return switch (reason) {
            case "stop" -> new MappedStopReason("stop", null);
            case "length", "model_length" -> new MappedStopReason("length", null);
            case "tool_calls" -> new MappedStopReason("toolUse", null);
            // pi mistral-conversations.ts:948（7fb59f995）：补 " (server error)" 让 retry
            // 白名单的 server.?error 抓手生效 —— Mistral 以此报告瞬态服务失败，应可重试。
            case "error" -> new MappedStopReason("error",
                "Provider stopped with: error (server error)");
            default -> new MappedStopReason("error", "Provider stopped with: " + reason);
        };
    }

    /** 映射结果：pi 的 {@code { stopReason, errorMessage? }}。 */
    private record MappedStopReason(String reason, String errorMessage) {}

    @SuppressWarnings("unchecked") // SSE response JSON parsing with generic Map types
    private void processSseData(String data,
                                  SubmissionPublisher<StreamEvent> publisher,
                                  StreamPartialBuilder builder,
                                  Map<String, ToolCallBuilder> toolBuilders,
                                  boolean[] textStarted,
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
                // Text delta
                var content = (String) delta.get("content");
                if (content != null && !content.isEmpty()) {
                    // pi mistral-conversations.ts:626 —— 流式文本增量净化（本车道唯一的响应面落点）。
                    var textDelta = SanitizeUnicode.surrogates(content);
                    if (!textStarted[0]) {
                        publisher.submit(builder.emitTextStart());
                        textStarted[0] = true;
                    }
                    publisher.submit(builder.emitTextDelta(textDelta));
                }

                // Tool call delta
                var toolCalls = (List<Map<String, Object>>) delta.get("tool_calls");
                if (toolCalls != null) {
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
                if ("toolUse".equals(stop.reason)) {
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

        return MAPPER.writeValueAsString(body);
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
