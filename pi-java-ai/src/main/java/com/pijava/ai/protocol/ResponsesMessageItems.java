package com.pijava.ai.protocol;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.openai.core.JsonValue;
import com.openai.core.ObjectMappers;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.ResponseReasoningItem;
import com.openai.models.responses.ResponseFunctionCallOutputItem;
import com.openai.models.responses.ResponseInputImageContent;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseInputTextContent;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseOutputText;

import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.api.TranscriptContext;
import com.pijava.ai.api.TranscriptTools;
import com.pijava.ai.api.Transcripts;
import com.pijava.ai.api.TransformMessages;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.MessageTexts;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.utils.SanitizeUnicode;
import com.pijava.ai.utils.ShortHash;

/**
 * OpenAI Responses 协议的「消息/工具结果 → {@link ResponseInputItem}」转换链
 * （从 {@code ResponsesMessageConverter} 抽出，结构债拆分——纯移动零行为变化）。
 */
final class ResponsesMessageItems {

    private ResponsesMessageItems() {}

    static List<ResponseInputItem> convertMessages(StreamRequest request,
                                                   TranscriptContext transcript,
                                                   String apiName,
                                                   TranscriptTools transcriptTools,
                                                   boolean supportsAdditionalTools,
                                                   boolean supportsToolSearch,
                                                   boolean supportsStrictMode,
                                                   Map<String, String> grammarProperties,
                                                   boolean instructionDeveloper) {
        // B222：系统指令项的角色（developer 仅推理模型；pi shared:214-215）。
        EasyInputMessage.Role instructionRole = instructionDeveloper
            ? EasyInputMessage.Role.DEVELOPER : EasyInputMessage.Role.SYSTEM;
        var items = new ArrayList<ResponseInputItem>();
        // docs/05 B152：**回放侧**的同一次去重 —— 修复之前落盘的转录里已经带着重复的
        // tool call（同 `call_id`），只靠流解析的去重救不回来 ⇒ 那种会话会**永远** 400。
        // 判据同流侧：同 `call_id` 只认第一条（合法调用会拿到不同 call_id）。被丢掉的调用
        // 对应的 `function_call_output` 也要一起丢，否则输出引用了输入里不存在的 call_id。
        // ⚠️ 两个 set 必须分开：调用与结果各自去重（共用一个会把**第一条结果**当成重复丢掉）。
        var emittedCalls = new HashSet<String>();
        var emittedOutputs = new HashSet<String>();
        // 系统文本来自**前导系统消息**（pi openai-responses-shared.ts:218-227 的
        // `sourceIndex++ === 0` 支 → getSystemMessageText），落成 input 里的指令项
        // —— 角色按 instructionRole（B222：推理模型 developer，否则 system）；
        // pi 在循环里就地转，折叠后头必在下标 0，故这里先落它。
        var initialSystemMessage = Transcripts.getInitialSystemMessage(transcript.messages());
        var systemText = initialSystemMessage == null
            ? "" : MessageTexts.getSystemMessageText(initialSystemMessage);
        if (!systemText.isEmpty()) {
            items.add(ResponseInputWire.inputMessage(instructionRole,
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
                            supportsAdditionalTools, supportsToolSearch, supportsStrictMode,
                            grammarProperties);
                    }
                    var update = MessageTexts.renderSystemMessageUpdate(system);
                    if (!update.isEmpty()) {
                        items.add(ResponseInputWire.inputMessage(instructionRole,
                            SanitizeUnicode.surrogates(update)));
                    }
                }
            } else if (msg instanceof Message.UserMessage user) {
                items.add(ResponseInputWire.toUserItem(user.content()));
            } else if (msg instanceof Message.AssistantMessage assistant) {
                addAssistantItems(items, assistant, msgIndex, request.modelId(), apiName,
                    grammarProperties, emittedCalls);
            } else if (msg instanceof Message.ToolResultMessage tool) {
                // 原 docs/69（pi shared:335-347）：grammar 工具的结果落 custom_tool_call_output。
                var callId = ResponsesToolCallIds.callIdOf(tool.toolUseId());
                // B152：同 call_id 的第二条结果一并丢掉（配对去重，见上面的两个 set）。
                if (emittedOutputs.add(callId)) {
                    var output = convertToolResultOutput(request.model(), tool.content());
                    items.add(ResponseToolWire.historyToolResult(callId, output,
                        grammarProperties.containsKey(tool.toolName())));
                }
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
     * <p>⚠️ 两处与 Anthropic 的 toolResult **刻意不同**（{@code 原 docs/44 D3}）：
     * ① {@code hasText} 为假时**不**推文本项（Anthropic 会补 {@code "(see attached image)"} 块）；
     * ② 净化发生在**join 之后**（{@code :86-88} 先 join("\n") 再净化）。</p>
     *
     * <p>⚠️ 能力门与 {@code "(see attached image)"} 分支在 pi 与 java **两侧都不可达** ——
     * 共享闸已按同一个 model 把非视觉模型的图片换成了文本块（{@code 原 docs/44 §9}）。照抄保留。</p>
     */
    private static ResponseInputItem.FunctionCallOutput.Output convertToolResultOutput(
            ModelInfo model, List<ContentBlock> content) {
        // pi :86-89 —— 文本块 join("\n")；图片单独收集。
        var text = content.stream()
                .filter(ContentBlock.TextContent.class::isInstance)
                .map(b -> ((ContentBlock.TextContent) b).text())
                .collect(java.util.stream.Collectors.joining("\n"));
        var images = content.stream()
                .filter(ResponsesMessageItems::isImageBlock)
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
     * （{@code 原 docs/44 D4 选项 A}；{@code toUserItem} 里早就是这个口径）。
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

    // 输入项（inputMessage/toUserItem）已抽到 ResponseInputWire（原 docs/66，步骤 7）。

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
     * {@code msg_pi_${msgIndex}} when there is none ({@code :267-287}); an id longer than 64
     * characters is replaced by {@code msg_${shortHash(id)}}. All three branches are live here
     * since 原 docs/71 G2 — {@link ContentBlock.TextContent#textSignature()} exists (Batch F added
     * it for Google) and this lane now writes it back too.</p>
     *
     * <p>⚠️ 早先的注释说「{@code ContentBlock.TextContent} carries no signature at all
     * （B23）」—— 那是 Batch F 之前的事实，已作废（见 原 docs/71 §2.2）。</p>
     */
    private static void addAssistantItems(List<ResponseInputItem> items,
                                          Message.AssistantMessage assistant,
                                          int msgIndex,
                                          ModelId<?> target, String apiName,
                                          Map<String, String> grammarProperties,
                                          Set<String> emittedCalls) {
        var textMessages = new ArrayList<ResponseInputItem>();
        var toolCalls = new ArrayList<ResponseInputItem>();
        var reasoningItems = new ArrayList<ResponseReasoningItem>();
        // pi :270 —— 只数**文本块**的序号（决定 `_${textBlockIndex}` 后缀）。
        int textBlockIndex = 0;
        for (var block : assistant.content()) {
            if (block instanceof ContentBlock.TextContent tc) {
                textMessages.add(outputMessage(tc, msgIndex, textBlockIndex++));
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
                // B152：同 call_id 的第二次调用不再发（连它的 output 一起丢，见调用方）。
                if (!emittedCalls.add(callId)) {
                    continue;
                }
                String itemId = ResponsesToolCallIds.itemIdOf(toolUse.id());
                boolean sameProviderAndApi = java.util.Objects.equals(assistant.provider(), target.provider())
                    && java.util.Objects.equals(assistant.api(), apiName);
                boolean differentModel = sameProviderAndApi
                    && !java.util.Objects.equals(assistant.model(), target.modelName());
                // 批 30（ai#8，pi shared :259）：namespace 回放的同模型闸门。
                boolean sameModel = sameProviderAndApi && !differentModel;
                boolean dropItemId =
                    (differentModel && itemId != null && itemId.startsWith("fc_"))
                    || (itemId != null && !itemId.startsWith("fc_"));
                toolCalls.add(ResponseToolWire.historyItem(toolUse, callId, itemId,
                    dropItemId, grammarProperties, sameModel));
            }
        }
        // Reasoning items precede the output messages; tool calls follow them.
        // ⚠️ 这与 pi 的**块序**（它在同一个循环里按块 push）有差：本仓按「reasoning ⇒
        // 文本 ⇒ 工具调用」分组。分组形状见 原 docs/71 §12 的裁决与登记。
        for (var reasoning : reasoningItems) {
            items.add(ResponseInputItem.ofReasoning(reasoning));
        }
        items.addAll(textMessages);
        items.addAll(toolCalls);
    }

    /**
     * pi {@code :267-287} —— 一个**文本块**一条 output message，id 走签名链。
     *
     * <p>id 次序：签名里的 id（新形 {@code {"v":1,…}} 或 legacy 裸串）⇒ 回退
     * {@code msg_pi_${msgIndex}}（首块）/ {@code msg_pi_${msgIndex}_${i}}（后续块）；
     * **超过 64 字符**时改 {@code msg_${shortHash(id)}} —— OpenAI 的硬上限（pi {@code :277}）。
     * {@code phase} 有值时随 item 上线。</p>
     */
    private static ResponseInputItem outputMessage(ContentBlock.TextContent block,
                                                   int msgIndex, int textBlockIndex) {
        var parsed = TextSignatureV1.parse(block.textSignature());
        var fallback = textBlockIndex == 0
            ? "msg_pi_" + msgIndex
            : "msg_pi_" + msgIndex + "_" + textBlockIndex;
        var signatureId = parsed.map(TextSignatureV1.Parsed::id).orElse(null);
        String msgId;
        if (signatureId == null || signatureId.isEmpty()) {
            msgId = fallback;
        } else if (signatureId.length() > 64) {
            msgId = "msg_" + ShortHash.of(signatureId);
        } else {
            msgId = signatureId;
        }
        var builder = ResponseOutputMessage.builder()
            .id(msgId)
            .role(JsonValue.from("assistant"))
            .status(ResponseOutputMessage.Status.COMPLETED)
            .content(List.of(ResponseOutputMessage.Content.ofOutputText(
                ResponseOutputText.builder()
                    // pi :283 —— 逐块净化（跨块边界的孤高+孤低在 pi 会被各自删除）。
                    .text(SanitizeUnicode.surrogates(block.text()))
                    .annotations(List.of())
                    .build())));
        parsed.map(TextSignatureV1.Parsed::phase)
            .ifPresent(phase -> builder.phase(ResponseOutputMessage.Phase.of(phase)));
        return ResponseInputItem.ofResponseOutputMessage(builder.build());
    }
}
