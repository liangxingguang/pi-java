package com.pijava.ai.protocol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.openai.core.JsonMissing;
import com.openai.core.JsonValue;
import com.openai.core.ObjectMappers;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseIncludable;
import com.openai.models.responses.ResponseReasoningItem;
import com.openai.models.responses.ResponseFunctionCallOutputItem;
import com.openai.models.responses.ResponseInputContent;
import com.openai.models.responses.ResponseInputImage;
import com.openai.models.responses.ResponseInputImageContent;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseInputText;
import com.openai.models.responses.ResponseInputTextContent;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseOutputText;
import com.openai.models.responses.ResponseToolSearchOutputItemParam;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.Tool;

import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.api.TranscriptContext;
import com.pijava.ai.api.TranscriptTools;
import com.pijava.ai.api.Transcripts;
import com.pijava.ai.api.TransformMessages;
import com.pijava.ai.catalog.CompatResolver;
import com.pijava.ai.catalog.ModelCompat;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.MessageTexts;
import com.pijava.ai.model.ModelId;
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

    /**
     * **只有这条车道**理会 {@code compat.supportsMaxOutputTokens} 这道门（包 A-10 第 6 步）。
     *
     * <p>pi 的两条 Responses 副本在这一点上**不对称**：{@code openai-responses.ts:321} 写
     * {@code if (options?.maxTokens && compat.supportsMaxOutputTokens)}，而
     * {@code azure-openai-responses.ts:309} 的对应行**光秃秃**（{@code if (options?.maxTokens)}）
     * —— azure 那份 compat 副本里连这个键都不存在。本仓两条车道共用本类 ⇒ 那份不对称只能落在
     * **车道名**上：门在这条车道上生效，在别的车道名上恒开（azure 侧显式写
     * {@code supportsMaxOutputTokens: false} 也无效，与 pi 一致）。
     *
     * <p>⚠️ 这个串必须与 {@code OpenAIResponsesApi.apiName()} 逐字一致（那里就是本键的命名处）。
     * 若将来两条车道拆成两个转换器，这道门要跟着走 —— {@code docs/57 §11} 与探针 M5
     * 是这条约束的登记处。</p>
     */
    private static final String MAX_OUTPUT_TOKENS_GATED_LANE = "openai-responses";

    private ResponsesMessageConverter() {}

    /**
     * 构建 Responses 流式请求参数。
     *
     * @param modelName 覆盖 model 字段（Azure 传部署名）
     * @param apiName   本车道的 api 名（{@code "openai-responses"} / {@code "azure-openai-responses"}），
     *                  交给共享预通道做同模型判定，**同时**决定 {@code max_output_tokens} 的
     *                  {@code supportsMaxOutputTokens} 门是否生效（包 A-10 第 6 步，
     *                  {@link #MAX_OUTPUT_TOKENS_GATED_LANE}）；**必须由调用方传**，因为两条车道
     *                  共用本类而这两件事在 pi 侧都随车道变（pi 侧同样是两条独立构建器分别调
     *                  transformMessages）
     * @param compat    本请求**解析后**的 compat（{@link CompatResolver#forResponses}）——
     *                  ⚠️ 车道缺省（{@code supportsStrictMode} 的 {@code false}／{@code true}）
     *                  由**调用方**在解析时喂进去（pi 的两车道 compat 缺省相反：
     *                  {@code openai-responses.ts:74} 是 {@code ?? false}，
     *                  {@code azure-openai-responses.ts:296} 是 {@code ?? true}）；本类只消费
     */
    static ResponseCreateParams buildParams(StreamRequest request, ResponsesOptions ropts,
                                            String modelName, String apiName,
                                            ModelCompat compat) {
        return buildParams(request, ropts, modelName, apiName, compat, java.util.Map.of());
    }

    /**
     * 包 B103：带逐请求会话亲和头的形态（调用方按 compat/format 组装）。
     */
    static ResponseCreateParams buildParams(StreamRequest request, ResponsesOptions ropts,
                                            String modelName, String apiName,
                                            ModelCompat compat,
                                            java.util.Map<String, String> affinityHeaders) {
        // pi openai-responses.ts:119 / azure-openai-responses.ts:77 —— 车道入口先
        // resolveTranscript，之后再构建请求。
        var transcript = Transcripts.resolveTranscript(request.transcript(), compat);
        // 包 A3（docs/51 §4.4）：Responses 的**两个独立锚定机制**（命中其一即可）——
        // `additional_tools` 优先，缺席时退回合成的 tool_search 对（pi :180、:185-208）。
        boolean supportsAdditionalTools = Boolean.TRUE.equals(compat.supportsAdditionalTools());
        boolean supportsToolSearch = Boolean.TRUE.equals(compat.supportsToolSearch());
        boolean supportsStrictMode = Boolean.TRUE.equals(compat.supportsStrictMode());
        var transcriptTools = Transcripts.resolveTranscriptTools(transcript.messages(),
                supportsAdditionalTools || supportsToolSearch);

        var builder = ResponseCreateParams.builder()
            .model(modelName)
            .store(false)
            .input(ResponseCreateParams.Input.ofResponse(
                convertMessages(request, transcript, apiName, transcriptTools,
                    supportsAdditionalTools, supportsToolSearch, supportsStrictMode)));

        var tools = new ArrayList<Tool>();
        for (var td : transcriptTools.requestTools()) {
            tools.add(ResponseToolWire.responseTool(td, supportsStrictMode, false));
        }
        if (!tools.isEmpty()) {
            builder.tools(tools);
        }

        if (request.maxTokens() > 0 && sendsMaxOutputTokens(apiName, compat)) {
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
            // pi openai-responses.ts:352 —— 仅此分支请求加密推理内容（xAI :359 排除）。
            builder.include(List.of(ResponseIncludable.REASONING_ENCRYPTED_CONTENT));
        }

        applyCacheRetention(builder, ropts);
        // 包 A-10：模型级采样参数（pi `openai-responses.ts:362-365`，**body 的最后一个变更**）。
        SamplingParamsWriter.applyToResponses(builder, request.model());
        // 包 B103：会话亲和头（pi openai-responses.ts:258-267）。
        affinityHeaders.forEach(builder::putAdditionalHeader);
        return builder.build();
    }

    // ── Message conversion ─────────────────────────────────────────────

    /**
     * 本车道是否发 {@code max_output_tokens}（包 A-10 第 6 步）。
     *
     * <p>门只在 {@link #MAX_OUTPUT_TOKENS_GATED_LANE} 上生效 —— 这是 pi 两条副本的不对称
     * （见该常量的 javadoc）。⚠️ 缺省是**开**：{@code null}（模型没写 compat）与
     * {@code true} 行为相同。</p>
     */
    private static boolean sendsMaxOutputTokens(String apiName, ModelCompat compat) {
        return !MAX_OUTPUT_TOKENS_GATED_LANE.equals(apiName)
            || Boolean.TRUE.equals(compat.supportsMaxOutputTokens());
    }

    private static List<ResponseInputItem> convertMessages(StreamRequest request,
                                                           TranscriptContext transcript,
                                                           String apiName,
                                                           TranscriptTools transcriptTools,
                                                           boolean supportsAdditionalTools,
                                                           boolean supportsToolSearch,
                                                           boolean supportsStrictMode) {
        var items = new ArrayList<ResponseInputItem>();
        // 系统文本来自**前导系统消息**（pi openai-responses-shared.ts:218-222 的
        // `sourceIndex++ === 0` 支 → getSystemMessageText），落成 input 里的
        // `{role:"system"}` 项 —— pi 在循环里就地转，折叠后头必在下标 0，故这里先落它。
        var initialSystemMessage = Transcripts.getInitialSystemMessage(transcript.messages());
        var systemText = initialSystemMessage == null
            ? "" : MessageTexts.getSystemMessageText(initialSystemMessage);
        if (!systemText.isEmpty()) {
            items.add(ResponseInputWire.inputMessage(EasyInputMessage.Role.SYSTEM,
                SanitizeUnicode.surrogates(systemText)));
        }
        // 共享预通道先于本车道的映射跑（pi openai-responses-shared.ts:172 在消息转换前调
        // transformMessages）—— 跨模型重放的 thinking 块在此降级为文本，否则本车道的
        // addAssistantItems 会把它连块带文本一起丢（见那里的注释）。
        var messages = TransformMessages.apply(transcript.messages(), request.modelId(), apiName,
                request.model(), ResponsesToolCallIds.create(apiName));
        // 包 A3（R7）：`requireOnlyLeadingSystemMessage` 已删 —— 中途系统消息现在有落线支。
        var msgIndex = 0;
        for (int i = 0; i < messages.size(); i++) {
            var msg = messages.get(i);
            // pi :218 —— `sourceIndex++ === 0 && msg.role === "system"`：判据是**下标 0**，
            // 不是「第一条系统消息」，也不能用对象身份（transformMessages 可能换过对象）。
            boolean isLeadingSystemMessage = i == 0 && msg instanceof Message.SystemMessage;
            if (msg instanceof Message.SystemMessage system) {
                // 前导系统消息已在上面落成 input 项（pi :218-222 的同一支）。
                if (!isLeadingSystemMessage) {
                    // 包 A3（pi :185-208 的 `appendSystemToolAdditions`）：就地锚定 ——
                    // 只有 `anchorsAdditions` 为真时后续声明才作为**增量**发出去，
                    // 否则它们已经在请求级 tools 字段里了。
                    if (transcriptTools.anchorsAdditions() && !system.toolsAdded().isEmpty()) {
                        ResponseToolWire.anchorSystemToolAdditions(items, system, msgIndex,
                            supportsAdditionalTools, supportsToolSearch, supportsStrictMode);
                    }
                    var update = MessageTexts.renderSystemMessageUpdate(system);
                    if (!update.isEmpty()) {
                        items.add(ResponseInputWire.inputMessage(EasyInputMessage.Role.SYSTEM,
                            SanitizeUnicode.surrogates(update)));
                    }
                }
            } else if (msg instanceof Message.UserMessage user) {
                items.add(ResponseInputWire.toUserItem(user.content()));
            } else if (msg instanceof Message.AssistantMessage assistant) {
                addAssistantItems(items, assistant, msgIndex, request.modelId(), apiName);
            } else if (msg instanceof Message.ToolResultMessage tool) {
                items.add(ResponseInputItem.ofFunctionCallOutput(
                    ResponseInputItem.FunctionCallOutput.builder()
                        // D3：tool result 只用复合 id 的 call_id 段（pi shared :331-346）。
                        .callId(ResponsesToolCallIds.callIdOf(tool.toolUseId()))
                        .output(convertToolResultOutput(request.model(), tool.content()))
                        .build()));
            }
            // pi :349 —— `if (!isLeadingSystemMessage) msgIndex++;`：**中途系统消息也算一个下标**
            // （回填 id 里的 `msg_pi_${msgIndex}` 与 tool_search 的种子都用它）。
            // 折叠支下中途系统消息为零 ⇒ 这条自增此前从未生效；A3 放开之后必须补上。
            if (!isLeadingSystemMessage) {
                msgIndex++;
            }
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

    // 输入项（inputMessage/toUserItem）已抽到 ResponseInputWire（docs/66，步骤 7）。

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
                                          int msgIndex,
                                          ModelId<?> target, String apiName) {
        var text = new StringBuilder();
        var toolCalls = new ArrayList<ResponseFunctionToolCall>();
        var reasoningItems = new ArrayList<ResponseReasoningItem>();
        for (var block : assistant.content()) {
            if (block instanceof ContentBlock.TextContent tc) {
                // pi :283 —— assistant 文本**逐块**净化（跨块边界的孤高+孤低在 pi 会被各自删除）。
                text.append(SanitizeUnicode.surrogates(tc.text()));
            } else if (block instanceof ContentBlock.ThinkingContent th) {
                // pi shared :261-266 —— thinkingSignature 里是整个 reasoning item，
                // 按块序重放。解析失败的签名无法重放 ⇒ 跳过该块（设计 R 裁决）。
                if (!th.signature().isEmpty()) {
                    try {
                        reasoningItems.add(ObjectMappers.jsonMapper()
                            .readValue(th.signature(), ResponseReasoningItem.class));
                    } catch (Exception parseFailure) {
                        // skip malformed item
                    }
                }
            } else if (block instanceof ContentBlock.ToolUseContent toolUse) {
                // D3（pi shared :288-303）：拆分复合 id，按跨模型/非 fc_ 规则丢 item.id。
                String callId = ResponsesToolCallIds.callIdOf(toolUse.id());
                String itemId = ResponsesToolCallIds.itemIdOf(toolUse.id());
                boolean sameProviderAndApi = java.util.Objects.equals(assistant.provider(), target.provider())
                    && java.util.Objects.equals(assistant.api(), apiName);
                boolean differentModel = sameProviderAndApi
                    && !java.util.Objects.equals(assistant.model(), target.modelName());
                boolean dropItemId =
                    (differentModel && itemId != null && itemId.startsWith("fc_"))
                    || (itemId != null && !itemId.startsWith("fc_"));
                var callBuilder = ResponseFunctionToolCall.builder()
                    .callId(callId)
                    .name(toolUse.name())
                    .arguments(toArgumentsJson(toolUse.arguments()));
                if (itemId != null && !dropItemId) {
                    callBuilder.id(itemId);
                }
                toolCalls.add(callBuilder.build());
            }
        }
        // Reasoning items precede the output message — the turn's block order is
        // reasoning then text/calls (responses output has at most one message).
        for (var reasoning : reasoningItems) {
            items.add(ResponseInputItem.ofReasoning(reasoning));
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

    /**
     * 工具声明的 {@code strict} 字段 —— pi {@code openai-responses-shared.ts:391-393}
     * 的逐字落法：**不支持就整个键不发，支持就明确发**（{@code strict} 的值本身是
     * {@code constrainedStrict ?? false}）。
     *
     * <p>⚠️ 两个车道的缺省**相反**，这是 pi 的事实而不是笔误：
     * {@code openai-responses.ts:74} 写 {@code ?? false}、
     * {@code azure-openai-responses.ts:296} 与 {@code :319} 写 {@code ?? true}。</p>
     *
     * <p>⚠️ 「不发」必须显式表达成 {@link JsonMissing}：SDK 把 {@code strict} 标成必填
     * （{@code FunctionTool.Builder.build()} → {@code checkRequired("strict", strict)}），
     * 而 {@code JsonMissing} 正是 SDK 公开的「本字段缺席」值 —— 它的类文档写的是
     * 「will cause a JSON field to be omitted from the serialized JSON entirely」
     * （{@code Values.kt:433-445}）。这不是绕过校验的技巧。B88 之前这里**根本不设**它，
     * 于是请求在**构建期**就抛 {@code IllegalStateException}，桩服务器零请求
     * （{@code docs/50 §7.1} 实测）。</p>
     *
     * <p>java 没有 {@code constrainedSampling}（{@code docs/50 §3 F6}），故 pi 的
     * {@code constrainedStrict ?? false} 退化为恒 {@code false}；{@code prefer}
     * （发 {@code true} ＋ 收紧 schema）与 {@code require}（不支持则抛）两支登记为
     * {@code docs/50 §10 L-A}，不在此处造投机骨架。</p>
     */
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

    /** pi: clampOpenAIPromptCacheKey —— 按码点截前 64（包 B103，委托 {@link PromptCacheKeys}）。 */
    private static String clampCacheKey(String key) {
        return PromptCacheKeys.clamp(key);
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
