package com.pijava.ai.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.openai.core.JsonValue;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionContentPartText;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;

import com.pijava.ai.catalog.ModelCompat;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.utils.SanitizeUnicode;

/**
 * Serializes an assistant message including its tool calls and its reasoning
 * (pi {@code openai-completions.ts:1285-1390}），从 {@code OpenAICompletionsMessageConverter}
 * 按 500 行规则拆出（同 {@link CompletionImageWire}/{@link CompletionToolWire} 先例）。
 *
 * <p>pi applies <b>two independent</b> reasoning rules here, and this class ports both:</p>
 * <ol>
 *   <li><b>Signature-driven replay</b> ({@code :1310-1318}), with <b>no provider gate</b>: the
 *       thinking block's signature is the wire name the provider sent that text under, so it
 *       is also the name to send it back under. Multiple blocks join with one {@code "\n"}.</li>
 *   <li><b>Empty-string backfill</b> ({@code :1356-1362}): deepseek-family relays reject
 *       assistant history without {@code reasoning_content}; guarded by the compat flag and
 *       {@code model.reasoning}, skipped when rule (i) already wrote that key.</li>
 * </ol>
 *
 * <p>B98（docs/51）：{@code requiresAssistantAfterToolResult} 让 content 默认 {@code ""}
 * （pi {@code :1294-1296}）；{@code requiresThinkingAsText} 把思考块回放成**普通文本块数组**
 * （{@code :1322-1330}，无标签、无 reasoning 字段）。</p>
 */
final class CompletionAssistantMessage {

    private static final ObjectMapper JSON = new ObjectMapper();

    private CompletionAssistantMessage() {
    }

    static void add(
            List<ChatCompletionMessageParam> wire,
            Message.AssistantMessage assistant, ModelInfo model, ModelCompat compat,
            Map<String, String> grammarProperties) {
        var text = new StringBuilder();
        var reasoning = new ArrayList<String>();
        String signature = null;
        // pi preservedReasoningDetails (:1301-1309): structured details suppress
        // the raw reasoning field (mutex, :1330).
        var preservedDetails = CompletionsReasoningWire.preserve(assistant);
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
                toolCalls.add(CompletionToolWire.historyToolCall(
                    toolUse, grammarProperties));
            }
        }
        var ab = ChatCompletionAssistantMessageParam.builder();
        boolean assistantAfterToolResult =
            Boolean.TRUE.equals(compat.requiresAssistantAfterToolResult());
        // B98（pi :1294-1296）：flag 开时 assistant 消息默认 content 是 "" 而不是省略
        // —— 只带 tool_calls 的助手消息也必须显式空串（下面各内容分支会覆盖它）。
        if (assistantAfterToolResult) {
            ab.content("");
        }
        if (!toolCalls.isEmpty()) {
            ab.toolCalls(toolCalls);
        }

        // B98（pi :1322-1330）：requiresThinkingAsText ⇒ thinking 块降级为**普通文本块**
        // 数组回放（思考在前、可见文本在后，无标签防模仿），且不发任何 reasoning 字段。
        boolean thinkingAsText =
            Boolean.TRUE.equals(compat.requiresThinkingAsText()) && !reasoning.isEmpty();

        // 规则 (i)：签名即线格名，原样发回 —— 仅在无 structured details 时走
        // （pi :1330 mutex：有 preserved ⇒ 不发裸 reasoning 字段）。
        boolean reasoningContentSent = false;
        if (thinkingAsText) {
            // 类型名较长，起别名缩短行（SDK 嵌套 union：assistant content 仅收 text/refusal）。
            var parts = new ArrayList<
                ChatCompletionAssistantMessageParam.Content
                    .ChatCompletionRequestAssistantMessageContentPart>();
            parts.add(textPart(String.join("\n\n", reasoning)));
            for (var block : assistant.content()) {
                if (block instanceof ContentBlock.TextContent tc) {
                    parts.add(textPart(SanitizeUnicode.surrogates(tc.text())));
                }
            }
            ab.contentOfArrayOfContentParts(parts);
        } else if (!text.isEmpty()) {
            ab.content(text.toString());
        }
        if (!thinkingAsText && preservedDetails == null
                && signature != null && CompletionsReasoningWire.isReasoningField(signature)) {
            ab.putAdditionalProperty(signature, JsonValue.from(String.join("\n", reasoning)));
            reasoningContentSent = "reasoning_content".equals(signature);
        }

        // 规则 (ii)：deepseek 一类 relay 的助手历史必带 reasoning_content，缺了就补空串。
        // ⚠️ 门是「reasoning_content 这个键还没被写」而不是「什么都没写」—— 签名是
        // `reasoning` 时 pi 会**两个字段都发**（reasoning 有内容、reasoning_content 空串）。
        // ⚠️ 第二个合取项 model.reasoning 是**同一条**门（pi :1357 写在一行里）：非推理模型
        // 即使挂在 deepseek 上也不补，否则等于给普通对话凭空塞一个推理字段。
        // ⚠️ 包 A7：第一个合取项现在来自**解析后**的 compat（探测已收进 CompatResolver，
        // 那段内联的 deepseek 判据随之删除）。
        // Structured details (pi :1373-1375). 经树（原 docs/58 R11）：普通 mapper
        // 转树后 JsonValue.from，避免 NON_NULL 静默丢键。
        if (preservedDetails != null) {
            ab.putAdditionalProperty("reasoning_details",
                JsonValue.from(JSON.valueToTree(preservedDetails)));
        }

        if (!reasoningContentSent
                && Boolean.TRUE.equals(compat.requiresReasoningContentOnAssistantMessages())
                && model.capabilities().contains(ModelCapability.THINKING)) {
            ab.putAdditionalProperty("reasoning_content", JsonValue.from(""));
        }

        // pi :1365-1372 —— 既无内容又无工具调用的助手消息整条丢掉（有 provider 不接受
        // 空助手消息）。⚠️ reasoning **不算内容**：只带 thinking 的消息就是要丢的那种；
        // 例外是 requiresThinkingAsText —— 思考在该分支已变成 content 文本块（pi 同形）。
        if (!text.isEmpty() || !toolCalls.isEmpty() || thinkingAsText) {
            wire.add(ChatCompletionMessageParam.ofAssistant(ab.build()));
        }
    }

    /** pi {@code {type:"text", text}} 助手内容块（思考与可见文本都走它）。 */
    private static ChatCompletionAssistantMessageParam.Content
            .ChatCompletionRequestAssistantMessageContentPart textPart(String text) {
        return ChatCompletionAssistantMessageParam.Content
            .ChatCompletionRequestAssistantMessageContentPart.ofText(
                ChatCompletionContentPartText.builder().text(text).build());
    }
}
