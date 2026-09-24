package com.pijava.ai.protocol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.openai.core.JsonValue;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFunctionCallOutputItem;
import com.openai.models.responses.ResponseInputContent;
import com.openai.models.responses.ResponseInputImage;
import com.openai.models.responses.ResponseInputImageContent;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseInputText;
import com.openai.models.responses.ResponseInputTextContent;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseOutputText;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.Tool;

import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.api.TransformMessages;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.thinking.ThinkingLevel;
import com.pijava.ai.utils.SanitizeUnicode;

/**
 * OpenAI Responses 协议的消息/工具转换与请求构建。
 *
 * <p>对齐 pi {@code openai-responses-shared.ts} 的 {@code convertResponsesMessages}
 * / {@code convertResponsesTools}，供 {@code OpenAIResponsesApi} 与
 * {@code AzureOpenAIResponsesApi} 共享。产出与 OpenAI Completions 路径一致的
 * {@link StreamRequest} 语义：system/user 消息映射为 {@code input_message}，
 * assistant 消息映射为 {@code message}（含 tool call 独立 item），工具结果映射为
 * {@code function_call_output}。</p>
 */
final class ResponsesMessageConverter {

    /** OpenAI Responses rejects max_output_tokens below 16. */
    static final int MIN_OUTPUT_TOKENS = 16;

    private ResponsesMessageConverter() {}

    /**
     * 构建 Responses 流式请求参数。
     *
     * @param modelName 覆盖 model 字段（Azure 传部署名）
     * @param apiName   本车道的 api 名（{@code "openai-responses"} / {@code "azure-openai-responses"}），
     *                  交给共享预通道做同模型判定；**必须由调用方传**，因为两条车道共用本类
     *                  而这个串不同（pi 侧同样是两条独立构建器分别调 transformMessages）
     */
    static ResponseCreateParams buildParams(StreamRequest request, ResponsesOptions ropts,
                                            String modelName, String apiName) {
        var builder = ResponseCreateParams.builder()
            .model(modelName)
            .store(false)
            .input(ResponseCreateParams.Input.ofResponse(
                convertMessages(request, apiName)));

        var tools = new ArrayList<Tool>();
        for (var td : request.tools()) {
            tools.add(Tool.ofFunction(FunctionTool.builder()
                .name(td.name())
                .description(td.description())
                .parameters(FunctionTool.Parameters.builder()
                    .putAllAdditionalProperties(toJsonValues(td.inputSchema()))
                    .build())
                .build()));
        }
        if (!tools.isEmpty()) {
            builder.tools(tools);
        }

        if (request.maxTokens() > 0) {
            builder.maxOutputTokens(Math.max(request.maxTokens(), MIN_OUTPUT_TOKENS));
        }
        if (request.temperature() >= 0) {
            builder.temperature(request.temperature());
        }
        if (ropts.serviceTier() != null) {
            builder.serviceTier(ResponseCreateParams.ServiceTier.of(ropts.serviceTier()));
        }

        String effort = effortString(ropts.reasoningEffort());
        if (effort != null || ropts.reasoningSummary() != null) {
            var rb = Reasoning.builder();
            if (effort != null) {
                rb.effort(ReasoningEffort.of(effort));
            }
            rb.summary(Reasoning.Summary.of(
                ropts.reasoningSummary() != null ? ropts.reasoningSummary() : "auto"));
            builder.reasoning(rb.build());
        }

        applyCacheRetention(builder, ropts);
        return builder.build();
    }

    // ── Message conversion ─────────────────────────────────────────────

    private static List<ResponseInputItem> convertMessages(StreamRequest request, String apiName) {
        var items = new ArrayList<ResponseInputItem>();
        // 系统提示是请求上的独立字段（pi openai-responses-shared.ts:175 读
        // context.systemPrompt），不在消息列表里。
        var systemPrompt = request.systemPrompt();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            items.add(inputMessage(EasyInputMessage.Role.SYSTEM,
                SanitizeUnicode.surrogates(systemPrompt)));
        }
        // 共享预通道先于本车道的映射跑（pi openai-responses-shared.ts:172 在消息转换前调
        // transformMessages）—— 跨模型重放的 thinking 块在此降级为文本，否则本车道的
        // addAssistantItems 会把它连块带文本一起丢（见那里的注释）。
        var messages = TransformMessages.apply(request.messages(), request.modelId(), apiName,
                request.model(), ResponsesToolCallIds.create(apiName));
        var msgIndex = 0;
        for (var msg : messages) {
            if (msg instanceof Message.UserMessage user) {
                items.add(toUserItem(user.content()));
            } else if (msg instanceof Message.AssistantMessage assistant) {
                addAssistantItems(items, assistant, msgIndex);
            } else if (msg instanceof Message.ToolResultMessage tool) {
                items.add(ResponseInputItem.ofFunctionCallOutput(
                    ResponseInputItem.FunctionCallOutput.builder()
                        .callId(tool.toolUseId())
                        .output(convertToolResultOutput(request.model(), tool.content()))
                        .build()));
            }
            // pi 在循环体末尾自增（openai-responses-shared.ts:349），且**每种角色**都算一个
            // 下标 —— 回填 id 里的 `msg_pi_${msgIndex}` 用的是这个全量下标，不是「第几条助手消息」。
            msgIndex++;
        }
        return items;
    }

    /**
     * 工具结果的 output 落线 —— pi {@code openai-responses-shared.ts:78-110} 的
     * {@code convertToolResultOutput}，逐分支对照：
     *
     * <pre>
     * 无图片 **或** 模型不支持图片 ⇒ 返回**字符串**
     *     hasText ? text : images&gt;0 ? "(see attached image)" : "(no tool output)"   // :91-93
     * 否则返回**块数组**：hasText 时先推 input_text，再逐个推 input_image(detail:"auto")  // :95-107
     * </pre>
     *
     * <p>⚠️ 两处与 Anthropic 的 toolResult **刻意不同**（{@code docs/44 D3}）：
     * ① {@code hasText} 为假时**不**推文本项（Anthropic 会补 {@code "(see attached image)"} 块）；
     * ② 净化发生在**join 之后**（{@code :86-88} 先 join("\n") 再净化）。</p>
     *
     * <p>⚠️ 能力门与 {@code "(see attached image)"} 分支在 pi 与 java **两侧都不可达** ——
     * 共享闸已按同一个 model 把非视觉模型的图片换成了文本块（{@code docs/44 §9}）。照抄保留。</p>
     */
    private static ResponseInputItem.FunctionCallOutput.Output convertToolResultOutput(
            ModelInfo model, List<ContentBlock> content) {
        // pi :86-89 —— 文本块 join("\n")；图片单独收集。
        var text = content.stream()
                .filter(ContentBlock.TextContent.class::isInstance)
                .map(b -> ((ContentBlock.TextContent) b).text())
                .collect(java.util.stream.Collectors.joining("\n"));
        var images = content.stream()
                .filter(ResponsesMessageConverter::isImageBlock)
                .toList();
        boolean hasText = !text.isEmpty();

        if (images.isEmpty() || !model.supportsImageInput()) {
            // pi :92/:97 —— 净化的是**选中之后**的串（含占位串；占位串是纯 ASCII，
            // 净化是恒等变换，口径与 pi 一致）。
            return ResponseInputItem.FunctionCallOutput.Output.ofString(SanitizeUnicode.surrogates(
                hasText ? text : !images.isEmpty() ? "(see attached image)" : "(no tool output)"));
        }

        var output = new ArrayList<ResponseFunctionCallOutputItem>(images.size() + 1);
        if (hasText) {
            output.add(ResponseFunctionCallOutputItem.ofInputText(
                ResponseInputTextContent.builder()
                    .text(SanitizeUnicode.surrogates(text))
                    .build()));
        }
        for (var block : images) {
            output.add(ResponseFunctionCallOutputItem.ofInputImage(
                ResponseInputImageContent.builder()
                    .detail(ResponseInputImageContent.Detail.AUTO)
                    .imageUrl(imageUrl(block))
                    .build()));
        }
        return ResponseInputItem.FunctionCallOutput.Output.ofResponseFunctionCallOutputItemList(output);
    }

    /**
     * pi 的图片判据是 {@code type === "image"}；java 的 URL 图片同等对待并**原样**下发
     * （{@code docs/44 D4 选项 A}；{@code toUserItem} 里早就是这个口径）。
     */
    private static boolean isImageBlock(ContentBlock block) {
        return block instanceof ContentBlock.ImageContent
                || block instanceof ContentBlock.UrlImageContent;
    }

    private static String imageUrl(ContentBlock block) {
        if (block instanceof ContentBlock.UrlImageContent url) {
            return url.url();
        }
        var img = (ContentBlock.ImageContent) block;
        return "data:" + img.mediaType() + ";base64," + img.data();
    }

    private static ResponseInputItem inputMessage(EasyInputMessage.Role role, String text) {        return ResponseInputItem.ofEasyInputMessage(EasyInputMessage.builder()
            .role(role)
            .content(EasyInputMessage.Content.ofTextInput(text))
            .build());
    }

    private static ResponseInputItem toUserItem(List<ContentBlock> content) {
        var hasImage = content.stream().anyMatch(b -> b instanceof ContentBlock.ImageContent
            || b instanceof ContentBlock.UrlImageContent);
        if (!hasImage) {
            // pi :231 —— user 串形态：整串净化。
            return inputMessage(EasyInputMessage.Role.USER,
                SanitizeUnicode.surrogates(extractText(content)));
        }
        var parts = new ArrayList<ResponseInputContent>();
        for (var block : content) {
            if (block instanceof ContentBlock.TextContent tc && !tc.text().isEmpty()) {
                // pi :238 —— user 有图分支：**逐项**净化。
                parts.add(ResponseInputContent.ofInputText(
                    ResponseInputText.builder().text(SanitizeUnicode.surrogates(tc.text())).build()));
            } else if (block instanceof ContentBlock.ImageContent img) {
                parts.add(ResponseInputContent.ofInputImage(ResponseInputImage.builder()
                    .detail(ResponseInputImage.Detail.AUTO)
                    .imageUrl("data:" + img.mediaType() + ";base64," + img.data())
                    .build()));
            } else if (block instanceof ContentBlock.UrlImageContent url) {
                parts.add(ResponseInputContent.ofInputImage(ResponseInputImage.builder()
                    .detail(ResponseInputImage.Detail.AUTO)
                    .imageUrl(url.url())
                    .build()));
            }
        }
        return ResponseInputItem.ofEasyInputMessage(EasyInputMessage.builder()
            .role(EasyInputMessage.Role.USER)
            .content(EasyInputMessage.Content.ofResponseInputMessageContentList(parts))
            .build());
    }

    /**
     * Append pi's replay items for one assistant message.
     *
     * <p>{@code msgIndex} is the message's index in the **whole** message list (pi
     * {@code openai-responses-shared.ts:184}/{@code :349}); it is only used to synthesize
     * the output-message {@code id}, which the Responses API treats as **required** — leaving
     * it unset makes the SDK throw before the request is ever sent
     * ({@code ResponseOutputMessage.Builder.build} → {@code Check.checkRequired},
     * registered as B22).</p>
     *
     * <p>pi derives that id from the text block's replay signature and only falls back to
     * {@code msg_pi_${msgIndex}} when there is none ({@code :228-237}). pi-java's
     * {@link ContentBlock.TextContent} carries no signature at all (registered as B23), so the
     * fallback branch is the only reachable one — and pi's 64-character hash branch
     * ({@code msg_${shortHash(msgId)}}) is unreachable for the same reason, hence not ported.</p>
     */
    private static void addAssistantItems(List<ResponseInputItem> items,
                                          Message.AssistantMessage assistant,
                                          int msgIndex) {
        var text = new StringBuilder();
        var toolCalls = new ArrayList<ResponseFunctionToolCall>();
        for (var block : assistant.content()) {
            if (block instanceof ContentBlock.TextContent tc) {
                // pi :283 —— assistant 文本**逐块**净化（跨块边界的孤高+孤低在 pi 会被各自删除）。
                text.append(SanitizeUnicode.surrogates(tc.text()));
            } else if (block instanceof ContentBlock.ToolUseContent toolUse) {
                toolCalls.add(ResponseFunctionToolCall.builder()
                    .callId(toolUse.id())
                    .id(normalizeItemId(toolUse.id()))
                    .name(toolUse.name())
                    .arguments(toArgumentsJson(toolUse.arguments()))
                    .build());
            }
            // ThinkingContent is not replayed in v1: replaying requires the
            // reasoning signature (ResponseReasoningItem), which StreamPartialBuilder
            // does not retain. OpenAI re-derives reasoning for the current turn.
        }
        if (!text.isEmpty()) {
            items.add(ResponseInputItem.ofResponseOutputMessage(
                ResponseOutputMessage.builder()
                    .id("msg_pi_" + msgIndex)
                    .role(JsonValue.from("assistant"))
                    .status(ResponseOutputMessage.Status.COMPLETED)
                    .content(List.of(ResponseOutputMessage.Content.ofOutputText(
                        ResponseOutputText.builder()
                            .text(text.toString())
                            .annotations(List.of())
                            .build())))
                    .build()));
        }
        for (var call : toolCalls) {
            items.add(ResponseInputItem.ofFunctionCall(call));
        }
    }

    // ── Tools ──────────────────────────────────────────────────────────

    private static Map<String, JsonValue> toJsonValues(Map<String, Object> schema) {
        var out = new LinkedHashMap<String, JsonValue>();
        schema.forEach((key, value) -> out.put(key, JsonValue.from(value)));
        return out;
    }

    private static String toArgumentsJson(Map<String, Object> arguments) {
        try {
            return JSON.writeValueAsString(arguments);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
        new com.fasterxml.jackson.databind.ObjectMapper();

    // ── Options ────────────────────────────────────────────────────────

    private static String effortString(ThinkingLevel level) {
        if (level == null) {
            return null;
        }
        return switch (level) {
            case ThinkingLevel.Minimal() -> "minimal";
            case ThinkingLevel.Low() -> "low";
            case ThinkingLevel.Medium() -> "medium";
            // ⚠️ 包H5：pi 的 responses 车道走 `clampThinkingLevel` ＋ `thinkingLevelMap`，
            // 不硬编码；这条平行路径**不在本包范围**（docs/46 §9），此处只为让新增的
            // `Max` 有分支 —— 行为与改动前的 `XHigh` 一致（都落到 "high"）。
            case ThinkingLevel.High(), ThinkingLevel.XHigh(), ThinkingLevel.Max() -> "high";
        };
    }

    private static void applyCacheRetention(ResponseCreateParams.Builder builder,
                                            ResponsesOptions ropts) {
        switch (ropts.cacheRetention()) {
            case NONE -> {
                // prompt_cache_key/retention omitted — no implicit prompt caching.
            }
            case LONG -> {
                if (ropts.sessionId() != null) {
                    builder.promptCacheKey(clampCacheKey(ropts.sessionId()));
                }
                builder.promptCacheRetention(ResponseCreateParams.PromptCacheRetention.of("24h"));
            }
            case SHORT -> {
                if (ropts.sessionId() != null) {
                    builder.promptCacheKey(clampCacheKey(ropts.sessionId()));
                }
            }
        }
    }

    /** pi: clampOpenAIPromptCacheKey —— key 上限 64 字符。 */
    private static String clampCacheKey(String key) {
        return key.length() > 64 ? key.substring(0, 64) : key;
    }

    /** OpenAI Responses 的 function_call item id 必须以 "fc_" 开头且 ≤64 字符。 */
    private static String normalizeItemId(String callId) {
        var base = "fc_" + callId;
        return base.length() > 64 ? base.substring(0, 64) : base;
    }

    private static String extractText(List<ContentBlock> blocks) {
        var sb = new StringBuilder();
        for (var block : blocks) {
            if (block instanceof ContentBlock.TextContent tc) {
                sb.append(tc.text());
            }
        }
        return sb.toString();
    }
}
