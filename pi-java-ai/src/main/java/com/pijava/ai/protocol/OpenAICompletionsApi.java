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

/**
 * OpenAI Chat Completions adapter using the official {@code openai-java} SDK.
 *
 * <p>Phase 2a: emits the full 13-event protocol with {@code partial} snapshots
 * via {@link StreamPartialBuilder}.</p>
 */
public class OpenAICompletionsApi extends AbstractChatApi {

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
                            var u = chunk.usage().get();
                            publisher.submit(builder.emitUsage(u.promptTokens(), u.completionTokens()));
                        }
                        continue;
                    }
                    var choice = chunk.choices().get(0);
                    var delta = choice.delta();

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
                        var u = chunk.usage().get();
                        publisher.submit(builder.emitUsage(u.promptTokens(), u.completionTokens()));
                    }
                }
            }
            // Emit block-end events before StreamDone, in **creation order** (pi :674-676)
            for (var end : blockEnds) {
                publisher.submit(end.get());
            }
            toolCall.finish(publisher::submit, builder);
            String reason = toolCall.started() ? "tool_use" : "stop";
            publisher.submit(builder.emitDone(reason));
        } catch (Exception e) {
            publisher.submit(builder.emitError("error", e));
        }
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
            builder.addSystemMessage(systemText);
        }

        // 共享预通道必须先于本车道的映射跑（pi openai-completions.ts:1212 在
        // convertMessages 之前调 transformMessages）：跨模型重放的带签名 thinking 块
        // 在此降级为文本，本车道才看得见那段文本。
        var messages = TransformMessages.apply(request.messages(), request.modelId(), apiName);

        for (var msg : messages) {
            if (msg instanceof Message.UserMessage) {
                var text = extractText(msg.content());
                if (!text.isEmpty()) builder.addUserMessage(text);
            } else if (msg instanceof Message.AssistantMessage assistant) {
                addAssistantMessage(builder, assistant, request.model(), baseUrl);
            } else if (msg instanceof Message.ToolResultMessage tool) {
                // Tool results must be sent back to the model, otherwise it
                // cannot see the outcome and keeps repeating the same tool
                // call (observed as duplicated write blocks in the TUI).
                builder.addMessage(ChatCompletionToolMessageParam.builder()
                    .toolCallId(tool.toolUseId())
                    .content(extractText(tool.content()))
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
                text.append(tc.text());
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
