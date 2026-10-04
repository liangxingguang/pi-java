package com.pijava.ai.protocol;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.SubmissionPublisher;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.openai.core.JsonField;
import com.openai.core.JsonValue;
import com.openai.core.ObjectMappers;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletionChunk;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.GrammarInputProperties;
import com.pijava.ai.api.Transcripts;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.catalog.CacheRetention;
import com.pijava.ai.catalog.CompatResolver;
import com.pijava.ai.http.ProviderRetry;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.stream.StreamPartialBuilder;

/**
 * OpenAI Chat Completions adapter using the official {@code openai-java} SDK.
 *
 * <p>Phase 2a: emits the full 13-event protocol with {@code partial} snapshots
 * via {@link StreamPartialBuilder}.</p>
 *
 * <h3>B20：stop reason 从线格读、收尾按 pi 严格判定（原 docs/31 §8.35.14）</h3>
 *
 * <p>修复前本车道**完全不读** {@code choice.finish_reason}，收尾固定发
 * {@code toolCall.started() ? "tool_use" : "stop"}。后果有两个：被 {@code length}
 * 截断的轮次在 pi-java 里记成正常结束（宿主层的截断判定因此永不命中），
 * 以及 {@code content_filter}/{@code network_error} 这类**服务端主动停**的信号
 * 整段丢弃。</p>
 *
 * <p>现在按 pi {@code openai-completions.ts} 逐行对齐：映射表 {@code :1550-1571}
 * （<b>未知值不抛</b>，落 {@code "error"} + {@code Provider finish_reason: X}），
 * 读取点 {@code :571-577}，收尾 {@code :678-695}。</p>
 *
 * <p><b>三个刻意的偏差/不可达点</b>（均为 pi-java 结构所致，非遗漏）：</p>
 * <ol>
 *   <li><b>abort 检查不可达</b>（pi {@code :678}/{@code :682}）：{@link StreamRequest}
 *       没有 signal（pi 的 {@code options.signal}）⇒ 中止上提到宿主层
 *       {@code PiLoopRunner.markAborted}（{@code :291-303}）。</li>
 *   <li><b>{@code default} 分支只可能命中 SDK 认得的值</b>：{@code openai-java 4.42}
 *       的 {@code FinishReason.Known} 只有 5 个（stop/length/tool_calls/content_filter/
 *       function_call），但 {@code asString()} 对**任意字符串**都给出线格原值
 *       （实测：{@code "network_error"} → {@code "network_error"}），故 pi 的
 *       {@code network_error} 特例与 {@code default} 都**可达**。
 *       ⚠️ 不要改用 {@code known()} —— 它对未知值**抛**
 *       {@code OpenAIInvalidDataException}（实测），而 pi 在这一格要的是
 *       「落 error 事件」而不是「抛」。同族的 Anthropic SDK 行为相反（未知值进
 *       {@code asKnown()}），两处都别照抄。</li>
 *   <li><b>空字符串≙缺席</b>：pi 写的是 {@code if (choice.finish_reason)} —— 对
 *       {@code ""} 为假 ⇒ 不发 {@code hasFinishReason}。SDK 侧 {@code ""} 是
 *       {@code Optional.of} 存在值，故必须显式过滤（见 {@link #rawFinishReason}）。</li>
 * </ol>
 */
public class OpenAICompletionsApi extends AbstractChatApi {

    /**
     * pi 累加器的 stop reason 初值（{@code openai-completions.ts:333}），非 pi 词汇表取值。
     * 收尾拿它当「一个 finish reason 都没观测到」的哨兵。
     */
    private static final String PENDING = "pending";

    @Override
    public String apiName() {
        return "openai-completions";
    }

    private final OpenAIClient client;
    protected final String apiKey;

    /**
     * The **effective** base URL this adapter talks to, after the {@link ApiOptions} override and
     * the OpenAI default have been applied.
     *
     * <p>Kept because replay has to read it: pi's {@code detectCompat} classifies relay houses from
     * {@code model.baseUrl} ({@code openai-completions.ts:1592}), and pi-java's {@code ModelInfo}
     * carries no base URL — the effective one lives here. Detecting at request time (rather than
     * baking a flag into the catalog) is what makes {@code --base-url} and settings overrides
     * visible to the decision.</p>
     */
    protected final String baseUrl;

    /**
     * Create an adapter for the given options.
     *
     * @param options API options (apiKey or {@code OPENAI_API_KEY} required)
     */
    public OpenAICompletionsApi(ApiOptions options) {
        this(options, "OPENAI_API_KEY");
    }

    /**
     * Create an adapter for the given options, resolving the API key from an env var.
     *
     * @param options     API options (apiKey or env var required)
     * @param apiKeyEnvVar the environment variable holding the API key
     */
    public OpenAICompletionsApi(ApiOptions options, String apiKeyEnvVar) {
        this.apiKey = resolveApiKey(options, apiKeyEnvVar);
        this.baseUrl = options.baseUrl() != null && !options.baseUrl().isBlank()
                ? options.baseUrl() : "https://api.openai.com/v1";
        // A-14（G1）：SDK 内置重试关到 0，重试独占 ProviderRetry（pi requestOptions.maxRetries:0）。
        var clientBuilder = OpenAIOkHttpClient.builder()
                .apiKey(apiKey).baseUrl(baseUrl).maxRetries(0);
        // D-P1：models.json 合并来的 default headers（原 docs/65）。
        putExtraHeaders(options, clientBuilder::putHeader);
        this.client = clientBuilder.build();
        this.providerRetry = ProviderRetry.optionsOf(options);
        // 包 A-02（B105）：cacheRetention 的选项面（pi :342 的 options?.cacheRetention）。
        this.cacheRetention = retentionOf(options);
        // 包 B103：原始 sessionId（prompt_cache_key 用；非标准亲和头在 Java SDK streaming
        // 不可达，登记 B140）。
        this.sessionId = sessionIdOf(options);
    }

    /** 从 extra 读 sessionId（包 B103）；blank ⇒ null。 */
    private static String sessionIdOf(ApiOptions options) {
        Map<String, Object> extra = options.extra();
        Object raw = extra == null ? null : extra.get("sessionId");
        return raw == null || raw.toString().isBlank() ? null : raw.toString();
    }

    /** pi 的缓存保留期环境变量（与 {@code AnthropicMessagesApi} 同一个，:64 / :342 共用 helper）。 */
    private static final String CACHE_RETENTION_ENV = "PI_CACHE_RETENTION";

    /**
     * 请求期的 {@code cacheRetention} 选项（包 A-02）。三源合并（选项 ?? 环境变量 ?? short）
     * 在请求期做（{@link CompatResolver#resolveCacheRetention}，与 anthropic 车道同源）。
     */
    private final Optional<CacheRetention> cacheRetention;

    /** 包 B103：原始 sessionId（prompt_cache_key 用）。 */
    private final String sessionId;

    /** A-14：初始请求获取的重试选项（pi {@code ProviderRetryOptions}）。 */
    private final ProviderRetry.Options providerRetry;

    /**
     * 从 {@code ApiOptions.extra} 读 {@code cacheRetention} —— 与
     * {@code AnthropicMessagesApi.retentionOf} 逐字同形（两种来源都认：枚举值或线格字符串；
     * 非法串经 {@link CacheRetention#parse} 落成**缺席**，好让 {@code PI_CACHE_RETENTION}
     * 仍能生效）。
     */
    private static Optional<CacheRetention> retentionOf(ApiOptions options) {
        Map<String, Object> extra = options.extra();
        Object raw = extra == null ? null : extra.get("cacheRetention");
        if (raw instanceof CacheRetention retention) {
            return Optional.of(retention);
        }
        return CacheRetention.parse(raw == null ? null : raw.toString());
    }

    @Override
    protected void streamInternal(StreamRequest request,
                                   SubmissionPublisher<StreamEvent> publisher) {
        var builder = new StreamPartialBuilder();
        var stop = new StopState();
        // pi :396 —— 有没有**观测到** finish_reason，与 output.stopReason 是两个量：
        // 前者驱动严格判定，后者只记映射结果。
        boolean hasFinishReason = false;
        boolean textStarted = false;
        boolean thinkingStarted = false;
        // pi 收尾时按**建块序**发 text_end / thinking_end（openai-completions.ts:674-676），
        // 而建块序由线格决定（同一个 delta 里两者都有时 content 先处理、块先建）⇒ 记序，
        // 不写死「先 thinking 还是先 text」。
        var blockEnds = new ArrayDeque<Supplier<StreamEvent>>();
        var toolCall = new ToolCallAccumulator();
        // pi streamedReasoningDetails (:328): validated detail entries accumulated
        // across chunks, serialized once onto the thinking block at finalization.
        List<JsonNode> streamedDetails = null;
        // 原 docs/69（pi :338-341）：grammar 能力表请求起点一次算出，custom chunks 重组读它。
        var startCompat = CompatResolver.forCompletions(request.model(), baseUrl);
        var grammarProperties = GrammarInputProperties.create(
            Transcripts.getDeclaredTools(
                Transcripts.resolveTranscript(request.transcript(), startCompat).messages()),
            Boolean.TRUE.equals(startCompat.supportsOpenAIGrammarTools()));
        try {
            // 包 A-02（pi :342/:349-357）：cacheRetention 三源合并后进请求构建
            // —— 断点族（cacheControlOf）与 prompt_cache_retention 共用这一个值。
            var resolvedRetention = CompatResolver.resolveCacheRetention(cacheRetention,
                System.getenv(CACHE_RETENTION_ENV));
            var params = OpenAICompletionsMessageConverter.buildParams(request, apiName(), baseUrl,
                resolvedRetention, sessionId);
            publisher.submit(builder.emitStart());

            // A-14（R7）：只包初始请求获取；每次重试是全新 SDK 请求，X-Stainless-Retry-Count 恒 0。
            var streamResponse = ProviderRetry.retry(
                    () -> client.chat().completions().createStreaming(params),
                    ProviderRetry::ofOpenAi, providerRetry);
            try (streamResponse) {

                for (var chunk : streamResponse.stream().toList()) {
                    if (chunk.choices().isEmpty()) {
                        if (chunk.usage().isPresent()) {
                            publisher.submit(builder.emitUsage(
                                OpenAICompletionsUsage.parse(chunk.usage().get(), request.model())));
                        }
                        continue;
                    }
                    var choice = chunk.choices().get(0);

                    // pi `:566-573`：`choice.usage` 回退（Moonshot 型 relay 把 usage 放在
                    // choice 里而非顶层 chunk）。条件同时要求 `!chunk.usage` 与 choice 存在；
                    // 位置在 delta 处理**之前**（照 pi 的次序）。
                    if (chunk.usage().isEmpty()) {
                        var choiceUsage = OpenAICompletionsUsage.choiceUsage(choice._additionalProperties());
                        if (choiceUsage != null) {
                            publisher.submit(builder.emitUsage(
                                OpenAICompletionsUsage.parse(choiceUsage, request.model())));
                        }
                    }
                    var delta = choice.delta();

                    // finish_reason 先于 delta 处理（pi :571-577 就在 `if (choice.delta)` 之前）：
                    // 一个 chunk 同时带终局标记与内容时，pi 的 partial 是同对象、后发的 delta
                    // 已经看得见新 stopReason。pi-java 的快照在发事件时拍，故此处先后不影响
                    // 可观测结果 —— 仍照 pi 的次序放，免得后人以为顺序无关是「随便放」。
                    var rawFinishReason = rawFinishReason(choice);
                    if (rawFinishReason != null) {
                        // pi `:572`：原值先落消息（⑨/D5），映射结果再落 stop.reason。
                        builder.noteRawStopReason(rawFinishReason);
                        var mapped = mapStopReason(rawFinishReason);
                        stop.reason = mapped.reason();
                        if (mapped.errorMessage() != null) {
                            stop.errorMessage = mapped.errorMessage();
                        }
                        hasFinishReason = true;
                    }

                    // Text content
                    if (delta.content().isPresent()) {
                        var text = delta.content().get();
                        if (!text.isEmpty()) {
                            if (!textStarted) {
                                publisher.submit(builder.emitTextStart());
                                textStarted = true;
                                blockEnds.add(builder::emitTextEnd);
                            }
                            publisher.submit(builder.emitTextDelta(text));
                        }
                    }

                    // Reasoning (pi :597-620). The three wire names are not modelled by the
                    // SDK, so they are read off `_additionalProperties()`. The matched name
                    // becomes the block's signature — that is what lets replay send the text
                    // back under the **same** field without guessing (see addAssistantMessage).
                    var reasoning = CompletionReasoningFields.first(delta);
                    if (reasoning != null) {
                        if (!thinkingStarted) {
                            // pi rewrites the signature to "reasoning_content" for provider
                            // `opencode-go` (:615-617). Deliberately not ported: pi-java has no
                            // such provider (16 builtins + models.json) ⇒ the branch is
                            // unreachable. This is a decision, not an oversight.
                            publisher.submit(builder.emitThinkingStart("", reasoning.field(), false));
                            thinkingStarted = true;
                            blockEnds.add(builder::emitThinkingEnd);
                        }
                        publisher.submit(builder.emitThinkingDelta(reasoning.text()));
                    }

                    // Reasoning details (pi :664-675): array of structured detail
                    // objects on the delta; validated and merged into logical entries.
                    JsonField<?> detailsField = delta._additionalProperties().get("reasoning_details");
                    if (detailsField != null && detailsField.asArray().isPresent()) {
                        for (var jsonValue : detailsField.asArray().orElseThrow()) {
                            JsonNode detailNode =
                                ObjectMappers.jsonMapper().convertValue(jsonValue, JsonNode.class);
                            if (!CompletionReasoningDetails.isDetail(detailNode)) {
                                continue;
                            }
                            if (!thinkingStarted) {
                                publisher.submit(builder.emitThinkingStart("", "", false));
                                thinkingStarted = true;
                                blockEnds.add(builder::emitThinkingEnd);
                            }
                            if (streamedDetails == null) {
                                streamedDetails = new ArrayList<>();
                            }
                            CompletionReasoningDetails.append(streamedDetails, detailNode);
                        }
                    }

                    // Tool calls — start on the first chunk whatever it carries
                    // (id/name/arguments may arrive in separate chunks).
                    if (delta.toolCalls().isPresent()) {
                        for (var tc : delta.toolCalls().get()) {
                            var custom = CompletionsCustomChunks.from(tc);
                            if (custom != null) {
                                toolCall.updateCustom(tc.id().orElse(null), custom,
                                    grammarProperties, publisher::submit, builder);
                            } else {
                                var fn = tc.function();
                                toolCall.update(
                                    tc.id().orElse(null),
                                    fn.flatMap(f -> f.name()).orElse(null),
                                    fn.flatMap(f -> f.arguments()).orElse(null),
                                    publisher::submit, builder);
                            }
                        }
                    }

                    // Usage
                    if (chunk.usage().isPresent()) {
                        publisher.submit(builder.emitUsage(
                            OpenAICompletionsUsage.parse(chunk.usage().get(), request.model())));
                    }
                }
            }
            // Emit block-end events before StreamDone, in **creation order** (pi :674-676).
            // pi finishBlock applies streamed details before thinking_end (:440).
            stampStreamedDetails(builder, streamedDetails);
            for (var end : blockEnds) {
                publisher.submit(end.get());
            }
            toolCall.finish(publisher::submit, builder);

            // ⚠️ pi 的两处 abort 检查（:678 / :682）在车道层**结构上不可达** —— 见类 javadoc。
            // 中止由宿主层 PiLoopRunner.markAborted（:291-303）负责。

            // compat 缺席 ≙ true（pi 的 detected 是常量 true，detectCompat:1640）。
            // 请求不带模型元数据（StreamRequest of(ModelId…)）时同样取严格版。
            // 包 A7：走**解析后**的 compat —— 本标志是解析不变的（`resolved()` 原样透传），
            // 改过来是为了让「车道不直接读 `model.compat()`」这条验收 grep 成立。
            boolean supportsFinishReason = CompatResolver.forCompletions(request.model(), baseUrl)
                .supportsFinishReason();

            // pi :685-687 —— **容忍版**：有 finish_reason 能力却没观测到时，就地改写成正常结束。
            // pi 里这条恒不生效（detected 恒 true），只有 models.json 显式写 false 才放开；
            // 保留下来是因为它是 D1 严格判定的**对照面**（放宽的唯一途径）。
            if (!hasFinishReason && !supportsFinishReason) {
                stop.reason = toolCall.started() ? "toolUse" : "stop";
            }
            // pi :688-690 —— 先判 error：走 error 通道且**不再**发 done。
            // 兜底文案是 pi 逐字（与其他三条车道的措辞不同，别「统一」）。
            if ("error".equals(stop.reason)) {
                publisher.submit(builder.emitError("error", new IllegalStateException(
                    stop.errorMessage != null ? stop.errorMessage
                        : "Provider returned an error stop reason")));
                return;
            }
            // pi :691-693 —— 严格版：**没观测到** finish_reason 是错误，不是「正常结束」。
            // 后半（累加器仍停在 pending）在 pi 里结构上不可达（上面的容忍分支已改写它），
            // 保留原样以与 pi 那一行逐字对应。
            if ((supportsFinishReason && !hasFinishReason) || PENDING.equals(stop.reason)) {
                publisher.submit(builder.emitError("error",
                    new IllegalStateException("Stream ended without finish_reason")));
                return;
            }
            publisher.submit(builder.emitDone(stop.reason));
        } catch (Exception e) {
            // pi catch (:703-704) applies details onto thinking blocks before
            // emitting the error, so the error partial keeps replay metadata.
            stampStreamedDetails(builder, streamedDetails);
            publisher.submit(builder.emitError("error", e));
        }
    }

    /** Serialize the accumulated reasoning details onto the current thinking block. */
    private static void stampStreamedDetails(StreamPartialBuilder builder,
                                             List<JsonNode> details) {
        if (details == null) {
            return;
        }
        try {
            builder.applyThinkingSignature(
                ObjectMappers.jsonMapper().writeValueAsString(details), false);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize reasoning details", e);
        }
    }

    /**
     * 线格上的 {@code finish_reason} 原值，缺席/JSON null/**空串**都给 {@code null}
     * （pi {@code openai-completions.ts:571} 的 {@code if (choice.finish_reason)} 是**真值**判断）。
     *
     * <p>⚠️ 用 {@code asString()}（线格原值），**不要**用 {@code known()}：后者对 SDK 不认识的
     * 取值抛 {@code OpenAIInvalidDataException}（实测 {@code 4.42.0}），而 pi 在那种取值上要的是
     * 「落 error 事件 + 文案」。</p>
     *
     * @param choice 本帧的 choice
     * @return 非空的原值，或 {@code null} 表示「没有 finish_reason」
     */
    private static String rawFinishReason(ChatCompletionChunk.Choice choice) {
        return choice.finishReason()
            .map(fr -> fr.asString())
            .filter(raw -> !raw.isEmpty())
            .orElse(null);
    }

    /**
     * pi {@code mapStopReason}（{@code openai-completions.ts:1550-1571}）。
     *
     * <p>与 Anthropic 车道的关键差别：**未知取值不抛**，而是落 {@code "error"} +
     * {@code Provider finish_reason: X}。{@code toolUse} 与 pi 同字面量（B109 起，
     * 边界翻译已删 —— 见 {@code 原 docs/56}）。</p>
     *
     * @param reason 线格原值（保证非空，见 {@link #rawFinishReason}）
     * @return 映射后的 pi-java stop reason + 可选错误文案
     */
    private static MappedStopReason mapStopReason(String reason) {
        return switch (reason) {
            case "stop", "end" -> new MappedStopReason("stop", null);
            case "length" -> new MappedStopReason("length", null);
            case "function_call", "tool_calls" -> new MappedStopReason("toolUse", null);
            case "content_filter" ->
                new MappedStopReason("error", "Provider finish_reason: content_filter");
            case "network_error" ->
                new MappedStopReason("error", "Provider finish_reason: network_error");
            default -> new MappedStopReason("error", "Provider finish_reason: " + reason);
        };
    }

    /** 映射结果（pi 的 {@code {stopReason, errorMessage?}}）。 */
    private record MappedStopReason(String reason, String errorMessage) {}

    /** 累加器的 stop reason 状态（pi 的 {@code output.stopReason}/{@code errorMessage} 那一对）。 */
    private static final class StopState {
        private String reason = PENDING;
        private String errorMessage;
    }

}
