package com.pijava.ai.protocol;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.SubmissionPublisher;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.core.JsonField;
import com.openai.core.JsonValue;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.chat.completions.ChatCompletionStreamOptions;
import com.openai.models.chat.completions.ChatCompletionTool;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;

import com.pijava.ai.api.ApiOptions;
import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.api.TransformMessages;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.stream.StreamPartialBuilder;
import com.pijava.ai.utils.SanitizeUnicode;

/**
 * OpenAI Chat Completions adapter using the official {@code openai-java} SDK.
 *
 * <p>Phase 2a: emits the full 13-event protocol with {@code partial} snapshots
 * via {@link StreamPartialBuilder}.</p>
 *
 * <h3>B20：stop reason 从线格读、收尾按 pi 严格判定（docs/31 §8.35.14）</h3>
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

    protected final OpenAIClient client;
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

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Wire names probed for reasoning text, **in probe order** — the first non-empty string
     * wins (pi {@code openai-completions.ts:597-620}).
     *
     * <p>⚠️ Do not "unify" this with the replay-side list of accepted signature names
     * ({@code :280-282}): pi keeps **two arrays with different orders on purpose**. This one
     * decides which field is read when a relay returns two of them at once (chutes.ai sends
     * both {@code reasoning_content} and {@code reasoning} with the same text, {@code :600-602}
     * ⇒ {@code reasoning_content} wins); the other is only an {@code includes} test, where
     * order cannot matter.</p>
     */
    private static final List<String> REASONING_PROBE_FIELDS =
        List.of("reasoning_content", "reasoning", "reasoning_text");

    /**
     * Wire names accepted as a thinking block's **signature** when replaying it back
     * (pi {@code openai-completions.ts:278}). The signature is what the collect side stamped
     * on the block — i.e. the field the provider actually sent — so replay sends the text
     * back under that same name instead of guessing (see {@code addAssistantMessage}).
     *
     * <p>⚠️ Deliberately a **second array**, not a reuse of {@link #REASONING_PROBE_FIELDS}:
     * pi's two lists are in different orders ({@code :597} probes {@code reasoning_content}
     * first, {@code :278} lists {@code reasoning} first). Here the order cannot matter (it is
     * an {@code includes} test), but the pair exists for two different questions and must not
     * be collapsed.</p>
     */
    private static final List<String> REASONING_SIGNATURE_FIELDS =
        List.of("reasoning", "reasoning_content", "reasoning_text");

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
        this.client = OpenAIOkHttpClient.builder()
                .apiKey(apiKey).baseUrl(baseUrl).build();
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
        try {
            var params = buildParams(request, apiName(), baseUrl);
            publisher.submit(builder.emitStart());

            try (var streamResponse = client.chat().completions().createStreaming(params)) {

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
                    var reasoning = firstReasoningField(delta);
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

                    // Tool calls — accumulate deltas; emit ToolCallEnd at finish.
                    // id / name / arguments may arrive in separate chunks
                    // (DeepSeek etc.); start on the first chunk whatever it
                    // contains so the call is never dropped.
                    if (delta.toolCalls().isPresent()) {
                        for (var tc : delta.toolCalls().get()) {
                            var fn = tc.function();
                            toolCall.update(
                                tc.id().orElse(null),
                                fn.flatMap(f -> f.name()).orElse(null),
                                fn.flatMap(f -> f.arguments()).orElse(null),
                                publisher::submit, builder);
                        }
                    }

                    // Usage
                    if (chunk.usage().isPresent()) {
                        publisher.submit(builder.emitUsage(
                            OpenAICompletionsUsage.parse(chunk.usage().get(), request.model())));
                    }
                }
            }
            // Emit block-end events before StreamDone, in **creation order** (pi :674-676)
            for (var end : blockEnds) {
                publisher.submit(end.get());
            }
            toolCall.finish(publisher::submit, builder);

            // ⚠️ pi 的两处 abort 检查（:678 / :682）在车道层**结构上不可达** —— 见类 javadoc。
            // 中止由宿主层 PiLoopRunner.markAborted（:291-303）负责。

            // compat 缺席 ≙ true（pi 的 detected 是常量 true，detectCompat:1638）。
            // 请求不带模型元数据（StreamRequest.of(ModelId…)）时同样取严格版。
            boolean supportsFinishReason = request.model() == null
                || request.model().compat().supportsFinishReason();

            // pi :685-687 —— **容忍版**：有 finish_reason 能力却没观测到时，就地改写成正常结束。
            // pi 里这条恒不生效（detected 恒 true），只有 models.json 显式写 false 才放开；
            // 保留下来是因为它是 D1 严格判定的**对照面**（放宽的唯一途径）。
            if (!hasFinishReason && !supportsFinishReason) {
                stop.reason = toolCall.started() ? "tool_use" : "stop";
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
            publisher.submit(builder.emitError("error", e));
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
     * {@code Provider finish_reason: X}。{@code toolUse} 按 pi-java 的词表写作
     * {@code "tool_use"}（边界翻译在 {@code PiMessagesApi:244-246}）。</p>
     *
     * @param reason 线格原值（保证非空，见 {@link #rawFinishReason}）
     * @return 映射后的 pi-java stop reason + 可选错误文案
     */
    private static MappedStopReason mapStopReason(String reason) {
        return switch (reason) {
            case "stop", "end" -> new MappedStopReason("stop", null);
            case "length" -> new MappedStopReason("length", null);
            case "function_call", "tool_calls" -> new MappedStopReason("tool_use", null);
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

    /**
     * Find the delta's reasoning text: the first **non-empty string** among
     * {@link #REASONING_PROBE_FIELDS}, together with the name it arrived under
     * (pi {@code openai-completions.ts:597-620}).
     *
     * <p>pi reads them off {@code choice.delta as Record<string, unknown>} and guards with
     * {@code typeof value === "string"} — a numeric or object value is not reasoning, which is
     * why {@link JsonValue#asString()} (empty for those) is the right accessor.</p>
     *
     * @param delta the chunk's delta
     * @return the field name and text, or {@code null} when the delta carries no reasoning
     */
    private static ReasoningField firstReasoningField(ChatCompletionChunk.Choice.Delta delta) {
        var extra = delta._additionalProperties();
        for (var field : REASONING_PROBE_FIELDS) {
            JsonField<?> raw = extra.get(field);
            if (raw == null) {
                continue;
            }
            var text = raw.asString().orElse(null);
            if (text != null && !text.isEmpty()) {
                return new ReasoningField(field, text);
            }
        }
        return null;
    }

    /**
     * A reasoning value found on a delta.
     *
     * @param field the wire name it arrived under. Replayed **verbatim** as the thinking block's
     *              signature (pi {@code :615-618}) — that self-description is the whole point:
     *              the replay side reads the signature instead of guessing a field name
     * @param text  the non-empty reasoning text
     */
    private record ReasoningField(String field, String text) {}

    /**
     * Build the wire request.
     *
     * @param request the stream request
     * @param apiName the lane's name, used by the shared pre-pass
     *                ({@link TransformMessages}) to decide what to downgrade
     * @param baseUrl the adapter's **effective** base URL; replay needs it because the
     *                {@code deepseek}-family relay detection reads it (pi
     *                {@code detectCompat:1592} classifies from {@code model.baseUrl})
     * @return the request body
     */
    static ChatCompletionCreateParams buildParams(StreamRequest request, String apiName,
                                                  String baseUrl) {
        var builder = ChatCompletionCreateParams.builder()
                .model(request.modelId().modelName());

        // 系统提示是请求上的独立字段（pi openai-completions.ts:1214 读 context.systemPrompt），
        // 不在消息列表里。
        var systemText = request.systemPrompt();
        if (systemText != null && !systemText.isEmpty()) {
            // pi openai-completions.ts:1251 —— instruction/system 文本净化。
            builder.addSystemMessage(SanitizeUnicode.surrogates(systemText));
        }

        // 共享预通道必须先于本车道的映射跑（pi openai-completions.ts:1212 在
        // convertMessages 之前调 transformMessages）：跨模型重放的带签名 thinking 块
        // 在此降级为文本，本车道才看得见那段文本。
        var messages = TransformMessages.apply(request.messages(), request.modelId(), apiName,
                request.model());

        for (var msg : messages) {
            if (msg instanceof Message.UserMessage) {
                var text = extractText(msg.content());
                // pi :1257/:1264 —— user 内容净化。java 的 user 恒为**串形态**上线路
                // ⇒ 对齐 pi 的串分支 :1257（整串净化）。
                if (!text.isEmpty()) builder.addUserMessage(SanitizeUnicode.surrogates(text));
            } else if (msg instanceof Message.AssistantMessage assistant) {
                addAssistantMessage(builder, assistant, request.model(), baseUrl);
            } else if (msg instanceof Message.ToolResultMessage tool) {
                // Tool results must be sent back to the model, otherwise it
                // cannot see the outcome and keeps repeating the same tool
                // call (observed as duplicated write blocks in the TUI).
                builder.addMessage(ChatCompletionToolMessageParam.builder()
                    .toolCallId(tool.toolUseId())
                    // pi :1416 —— 净化的是**拼好之后**的串（pi 先 join("\n") 再净化）。
                    .content(SanitizeUnicode.surrogates(extractText(tool.content())))
                    .build());
            }
        }

        // Pass tools so the model emits structured tool_calls instead of
        // writing fake XML tool invocations into the text stream (which also
        // avoids garbled interleaving in the rendered bubble).
        for (var td : request.tools()) {
            builder.addTool(ChatCompletionTool.ofFunction(
                ChatCompletionFunctionTool.builder()
                    .type(JsonValue.from("function"))
                    .function(FunctionDefinition.builder()
                        .name(td.name())
                        .description(td.description())
                        .parameters(FunctionParameters.builder()
                            .putAllAdditionalProperties(toJsonValues(td.inputSchema()))
                            .build())
                        .build())
                    .build()));
        }
        // Ask for usage in the stream so the token counter/status bar updates.
        builder.streamOptions(ChatCompletionStreamOptions.builder()
            .includeUsage(true)
            .build());

        if (request.maxTokens() > 0) builder.maxCompletionTokens(request.maxTokens());
        if (request.temperature() >= 0) builder.temperature(request.temperature());

        return builder.build();
    }

    private static Map<String, JsonValue> toJsonValues(Map<String, Object> schema) {
        var out = new LinkedHashMap<String, JsonValue>();
        schema.forEach((key, value) -> out.put(key, JsonValue.from(value)));
        return out;
    }

    /**
     * Serializes an assistant message including its tool calls and its reasoning.
     *
     * <p>pi applies **two independent** reasoning rules here, and this method ports both:</p>
     * <ol>
     *   <li><b>Signature-driven replay</b> ({@code :1310-1318}), with **no provider gate**: the
     *       thinking block's signature is the wire name the provider sent that text under, so it
     *       is also the name to send it back under. Only signatures in
     *       {@link #REASONING_SIGNATURE_FIELDS} qualify; anything else (an Anthropic signature,
     *       say) is dropped rather than sent as an unknown parameter. Multiple blocks are joined
     *       with a single {@code "\n"}.</li>
     *   <li><b>Empty-string backfill</b> ({@code :1356-1362}): relay houses in the deepseek family
     *       reject assistant history that lacks {@code reasoning_content}, so one is added as
     *       {@code ""}. Guarded by the compat flag <i>and</i> {@code model.reasoning}, and skipped
     *       when rule (i) already set that exact key.</li>
     * </ol>
     *
     * @param builder  the request builder
     * @param assistant the assistant message to serialize
     * @param model    the request's target model — its capabilities give pi's
     *                 {@code model.reasoning}, its compat gives the explicit override
     * @param baseUrl  the adapter's effective base URL, for the deepseek relay detection
     */
    private static void addAssistantMessage(
            ChatCompletionCreateParams.Builder builder,
            Message.AssistantMessage assistant, ModelInfo model, String baseUrl) {
        var text = new StringBuilder();
        var reasoning = new ArrayList<String>();
        String signature = null;
        var toolCalls = new ArrayList<ChatCompletionMessageToolCall>();
        for (var block : assistant.content()) {
            if (block instanceof ContentBlock.TextContent tc) {
                // pi :1295 —— assistant 文本**逐块**净化后再 join("")（不是拼完再净化：
                // 跨块边界的孤高+孤低在 pi 会被各自删除，拼完再净化则会成对复活）。
                text.append(SanitizeUnicode.surrogates(tc.text()));
            } else if (block instanceof ContentBlock.ThinkingContent thinking) {
                // pi :1289 —— 纯空白块不算推理：既不进连接，也不参与签名的选取。
                if (thinking.text().trim().isEmpty()) {
                    continue;
                }
                // 签名取**第一个非空块**的（pi :1313 取 nonEmptyThinkingBlocks[0]），
                // 不是取第一个已知签名的 —— 块序在这里是有意义的。
                if (signature == null) {
                    signature = thinking.signature();
                }
                reasoning.add(thinking.text());
            } else if (block instanceof ContentBlock.ToolUseContent toolUse) {
                toolCalls.add(ChatCompletionMessageToolCall.ofFunction(
                    ChatCompletionMessageFunctionToolCall.builder()
                        .id(toolUse.id())
                        .function(ChatCompletionMessageFunctionToolCall.Function.builder()
                            .name(toolUse.name())
                            .arguments(toArgumentsJson(toolUse.arguments()))
                            .build())
                        .build()));
            }
        }
        var ab = ChatCompletionAssistantMessageParam.builder();
        if (!text.isEmpty()) {
            ab.content(text.toString());
        }
        if (!toolCalls.isEmpty()) {
            ab.toolCalls(toolCalls);
        }

        // 规则 (i)：签名即线格名，原样发回。pi 在这条路径上还有一层 reasoning_details
        // 分支（preservedReasoningDetails，OpenAI 加密推理详情）；pi-java 不解析该结构
        // ⇒ 那个 if 恒真，故不移植。
        boolean reasoningContentSent = false;
        if (signature != null && REASONING_SIGNATURE_FIELDS.contains(signature)) {
            ab.putAdditionalProperty(signature, JsonValue.from(String.join("\n", reasoning)));
            reasoningContentSent = "reasoning_content".equals(signature);
        }

        // 规则 (ii)：deepseek 一类 relay 的助手历史必带 reasoning_content，缺了就补空串。
        // ⚠️ 门是「reasoning_content 这个键还没被写」而不是「什么都没写」—— 签名是
        // `reasoning` 时 pi 会**两个字段都发**（reasoning 有内容、reasoning_content 空串）。
        // ⚠️ 第二个合取项 model.reasoning 是**同一条**门（pi :1357 写在一行里）：非推理模型
        // 即使挂在 deepseek 上也不补，否则等于给普通对话凭空塞一个推理字段。
        if (!reasoningContentSent
                && requiresReasoningContentOnAssistantMessages(model, baseUrl)
                && model.capabilities().contains(ModelCapability.THINKING)) {
            ab.putAdditionalProperty("reasoning_content", JsonValue.from(""));
        }

        // pi :1365-1372 —— 既无内容又无工具调用的助手消息整条丢掉（有 provider 不接受
        // 空助手消息）。⚠️ reasoning **不算内容**：只带 thinking 的消息就是要丢的那种。
        if (!text.isEmpty() || !toolCalls.isEmpty()) {
            builder.addMessage(ab.build());
        }
    }

    /**
     * pi {@code detectCompat:1592}：deepseek 家族靠 provider 名**或** baseUrl 判。
     *
     * <p>provider 名是精确比较（pi 是 {@code ===}），baseUrl 是小写子串匹配。models.json
     * 的 {@code compat} 显式给值时以它为准（pi 的 {@code getCompat} = {@code explicit ?? detected}）。</p>
     *
     * <p>⚠️ 这只回答 compat **那一半**；pi 的条件是它与 {@code model.reasoning} 的合取，
     * 调用点补上另一半。</p>
     *
     * @param model   the request's target model
     * @param baseUrl the adapter's effective base URL
     * @return {@code true} when this relay house demands {@code reasoning_content} on
     *         assistant history
     */
    private static boolean requiresReasoningContentOnAssistantMessages(ModelInfo model, String baseUrl) {
        var explicit = model.compat().requiresReasoningContentOnAssistantMessages();
        if (explicit != null) {
            return explicit;
        }
        return "deepseek".equals(model.id().provider())
            || (baseUrl != null && baseUrl.toLowerCase().contains("deepseek.com"));
    }

    private static String toArgumentsJson(Map<String, Object> arguments) {
        try {
            return JSON.writeValueAsString(arguments);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String extractText(List<ContentBlock> blocks) {
        var sb = new StringBuilder();
        for (var block : blocks) {
            if (block instanceof ContentBlock.TextContent tc) sb.append(tc.text());
        }
        return sb.toString();
    }
}
