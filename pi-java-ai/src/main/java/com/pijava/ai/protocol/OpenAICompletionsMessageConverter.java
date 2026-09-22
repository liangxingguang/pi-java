package com.pijava.ai.protocol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.core.JsonValue;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionContentPart;
import com.openai.models.chat.completions.ChatCompletionContentPartImage;
import com.openai.models.chat.completions.ChatCompletionContentPartText;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.chat.completions.ChatCompletionStreamOptions;
import com.openai.models.chat.completions.ChatCompletionTool;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;

import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.api.TransformMessages;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.utils.SanitizeUnicode;

/**
 * 「消息 → OpenAI-completions 线格」的转换 —— 从 {@link OpenAICompletionsApi} 抽出的请求面
 * （包 H2 收尾的拆文件提交；{@code OpenAICompletionsApi} 已 729 行，超 500 行上限）。
 *
 * <p><b>纯函数、零状态</b>：只依赖入参，不碰 client/流 —— 那些留在车道类里。
 * 入口是 {@link #buildParams}（车道与若干夹具都从它观测出参）。</p>
 *
 * <p>⚠️ 本车道的两处**刻意不对称**都有 pi 行号背书（{@code docs/44 D3}）：user 有图分支
 * **不过滤**空文本块；toolResult 的图片**移出** tool 消息、补一条合成 user 消息且**连续合并**。</p>
 *
 * @see OpenAICompletionsApi
 */
final class OpenAICompletionsMessageConverter {

    private OpenAICompletionsMessageConverter() {}

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Wire names accepted as a thinking block's **signature** when replaying it back
     * (pi {@code openai-completions.ts:278}). The signature is what the collect side stamped
     * on the block — i.e. the field the provider actually sent — so replay sends the text
     * back under that same name instead of guessing (see {@link #addAssistantMessage}).
     *
     * <p>⚠️ Deliberately a **second array** with a different order from the collect side's
     * probe list ({@code OpenAICompletionsApi.REASONING_PROBE_FIELDS}): pi's two lists are in
     * different orders ({@code :597} probes {@code reasoning_content} first, {@code :278} lists
     * {@code reasoning} first). Here the order cannot matter (it is an {@code includes} test),
     * but the pair exists for two different questions and must not be collapsed.</p>
     */
    private static final List<String> REASONING_SIGNATURE_FIELDS =
        List.of("reasoning", "reasoning_content", "reasoning_text");

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

        for (int i = 0; i < messages.size(); i++) {
            var msg = messages.get(i);
            if (msg instanceof Message.UserMessage user) {
                addUserMessage(builder, user);
            } else if (msg instanceof Message.AssistantMessage assistant) {
                addAssistantMessage(builder, assistant, request.model(), baseUrl);
            } else if (msg instanceof Message.ToolResultMessage) {
                // pi :1398-1455 —— **连续的** toolResult 合成一组：各自落一条 tool 消息，
                // 但图片**合并收集**进**同一条**合成 user 消息（不是一条结果配一条）。
                var imageParts = new ArrayList<ChatCompletionContentPart>();
                int j = i;
                while (j < messages.size()
                        && messages.get(j) instanceof Message.ToolResultMessage tool) {
                    builder.addMessage(ChatCompletionToolMessageParam.builder()
                        .toolCallId(tool.toolUseId())
                        // pi :1416 —— 净化的是**选中之后**的串（含两个占位串）。
                        .content(SanitizeUnicode.surrogates(toolResultText(tool.content())))
                        .build());
                    // pi :1424 —— 图片收集**另有**一道能力门（与共享闸冗余，pi 两处都写）。
                    // ⚠️ 这道门在 pi 与 pi-java **两侧都不可观察**：共享闸（TransformMessages）
                    // 已按同一个 model 把非视觉模型的图片换成了文本块 ⇒ 这里永远收不到图片。
                    // 照抄保留（pi 也保留），但**没有任何夹具能钉住它** —— 不是夹具没牙，
                    // 是这一行没有出参（docs/44 §9 的变异探针 4 实测：去掉它零红）。
                    if (request.model().supportsImageInput()) {
                        collectImageParts(tool.content(), imageParts);
                    }
                    j++;
                }
                i = j - 1;
                if (!imageParts.isEmpty()) {
                    // pi :1448-1456 —— 合成的 user 消息（文案逐字）。
                    builder.addMessage(syntheticToolImageMessage(imageParts));
                }
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

    // ── 图片（包 H2，docs/44 步3）──────────────────────────────────────

    /**
     * user 消息落线 —— pi {@code openai-completions.ts:1255-1277}。
     *
     * <p>无图片 ⇒ **串形态**（pi-java 的既有形状，见 {@code docs/44 §6} 的登记：pi 的串分支
     * 在 pi-java 结构上不可达 —— {@code UserMessage.content} 恒为列表）；有图片 ⇒ **数组形态**
     * {@code [{type:"text"},{type:"image_url",image_url:{url:"data:<mime>;base64,<data>"}}]}。</p>
     *
     * <p>⚠️ 有图分支**不过滤**空文本块（pi {@code :1267} 只判 {@code content.length === 0}）
     * —— 与 Anthropic 的 user 分支（过滤）**刻意不同**，别顺手统一（{@code docs/44 D3}）。</p>
     */
    private static void addUserMessage(ChatCompletionCreateParams.Builder builder,
                                       Message.UserMessage user) {
        boolean hasImages = user.content().stream().anyMatch(OpenAICompletionsMessageConverter::isImageBlock);
        if (!hasImages) {
            var text = extractText(user.content());
            // pi :1257 —— user 串形态：整串净化。
            if (!text.isEmpty()) builder.addUserMessage(SanitizeUnicode.surrogates(text));
            return;
        }
        var parts = new ArrayList<ChatCompletionContentPart>();
        for (var block : user.content()) {
            if (block instanceof ContentBlock.TextContent tc) {
                // pi :1261 —— 有图分支：**逐项**净化。
                parts.add(ChatCompletionContentPart.ofText(ChatCompletionContentPartText.builder()
                        .text(SanitizeUnicode.surrogates(tc.text())).build()));
            } else if (block instanceof ContentBlock.ImageContent img) {
                parts.add(imageUrlPart("data:" + img.mediaType() + ";base64," + img.data()));
            } else if (block instanceof ContentBlock.UrlImageContent url) {
                // java 扩展（pi 无此类型）：image_url 本来就收 URL ⇒ 按线格本名下发（docs/44 D4）。
                parts.add(imageUrlPart(url.url()));
            }
        }
        if (parts.isEmpty()) return; // pi :1267 —— `content.length === 0 ⇒ continue`
        builder.addMessage(ChatCompletionUserMessageParam.builder()
                .content(ChatCompletionUserMessageParam.Content.ofArrayOfContentParts(parts))
                .build());
    }

    /**
     * 工具结果的 tool 消息正文 —— pi {@code :1405-1412}：
     * 各文本块 {@code join("\n")} 后净化；空串时按「有图 ／ 无图」落两个占位串之一。
     *
     * <p>⚠️ 顺带的行为变更：旧实现是**无分隔符拼接**、且空串原样发（没有 {@code "(no tool output)"}）。
     * 两处都随本步与 pi 对齐。</p>
     */
    private static String toolResultText(List<ContentBlock> content) {
        var text = content.stream()
                .filter(ContentBlock.TextContent.class::isInstance)
                .map(b -> ((ContentBlock.TextContent) b).text())
                .collect(java.util.stream.Collectors.joining("\n"));
        if (!text.isEmpty()) return text;
        return content.stream().anyMatch(OpenAICompletionsMessageConverter::isImageBlock)
                ? "(see attached image)" : "(no tool output)";
    }

    /** 收集图片块（能力门在调用点，pi {@code :1424}）。 */
    private static void collectImageParts(List<ContentBlock> content,
                                          List<ChatCompletionContentPart> out) {
        for (var block : content) {
            if (block instanceof ContentBlock.ImageContent img) {
                out.add(imageUrlPart("data:" + img.mediaType() + ";base64," + img.data()));
            } else if (block instanceof ContentBlock.UrlImageContent url) {
                out.add(imageUrlPart(url.url()));
            }
        }
    }

    /**
     * pi {@code :1448-1456} 的合成 user 消息：一句固定文案 ＋ 收集到的图片块。
     * 文案是纯 ASCII 字面量，pi 也不净化。
     */
    private static ChatCompletionUserMessageParam syntheticToolImageMessage(
            List<ChatCompletionContentPart> imageParts) {
        var parts = new ArrayList<ChatCompletionContentPart>(imageParts.size() + 1);
        parts.add(ChatCompletionContentPart.ofText(ChatCompletionContentPartText.builder()
                .text("Attached image(s) from tool result:").build()));
        parts.addAll(imageParts);
        return ChatCompletionUserMessageParam.builder()
                .content(ChatCompletionUserMessageParam.Content.ofArrayOfContentParts(parts))
                .build();
    }

    private static ChatCompletionContentPart imageUrlPart(String url) {
        return ChatCompletionContentPart.ofImageUrl(ChatCompletionContentPartImage.builder()
                .imageUrl(ChatCompletionContentPartImage.ImageUrl.builder().url(url).build())
                .build());
    }

    /** pi 的图片判据是 {@code type === "image"}；java 的 URL 图片同等对待（docs/44 D4）。 */
    private static boolean isImageBlock(ContentBlock block) {
        return block instanceof ContentBlock.ImageContent
                || block instanceof ContentBlock.UrlImageContent;
    }
}
