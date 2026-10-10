package com.pijava.ai.protocol;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.pijava.ai.api.StreamRequest;
import com.pijava.ai.api.TranscriptContext;
import com.pijava.ai.api.TransformMessages;
import com.pijava.ai.api.Transcripts;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.MessageTexts;
import com.pijava.ai.utils.SanitizeUnicode;

/**
 * Mistral 线格消息转换 —— 从 {@link MistralConversationsApi} 拆出（文件行数纪律 ≤500），
 * 对应 pi {@code mistral-conversations.ts:787-874} 的消息映射段。纯静态、无状态。
 */
final class MistralMessages {

    private MistralMessages() {}

    /** 把过闸后的转录消息列表转成 Mistral chat 消息数组（pi {@code :787-874}）。 */
    static List<Map<String, Object>> toWire(TranscriptContext transcript,
                                            StreamRequest request,
                                            String apiName) {
        var messages = new ArrayList<Map<String, Object>>();
        // 系统提示来自**前导系统消息**（pi mistral-conversations.ts:787-789 的 `index === 0`
        // 支 → getSystemMessageText），Mistral 用一条 role=system 的消息承载它。
        // 折叠后头必在下标 0；原生中途系统消息路径归 A3/A7（原 docs/49 L-D）。
        var initialSystemMessage = Transcripts.getInitialSystemMessage(transcript.messages());
        if (initialSystemMessage != null) {
            var systemText = MessageTexts.getSystemMessageText(initialSystemMessage);
            if (!systemText.isEmpty()) {
                var system = new HashMap<String, Object>();
                system.put("role", "system");
                system.put("content", SanitizeUnicode.surrogates(systemText)); // pi :789
                messages.add(system);
            }
        }
        // 共享预通道先于本车道的映射跑（pi mistral-conversations.ts:139 在消息转换前调
        // transformMessages）—— 本车道的 extractText 只收 TextContent，
        // 不过闸则跨模型重放的 thinking 文本无声消失。
        // pi :517 —— 车道的图片能力位来自同一个 model.input.includes("image")。
        boolean supportsImages = request.model().supportsImageInput();
        // ⚠️ 与 pi 的差别只在**输入表**：pi 把系统消息留在表里、由 `:787` 的分支就地处理；
        // java 在转换前去掉头（上面已落线），故此处喂去头后的表。两者出参逐条相同。
        // 包 A3（R7）：`requireOnlyLeadingSystemMessage` 已删 —— 中途系统消息现在**有**落线支
        // （上面的 case），守卫从「响亮拒绝」变成死码。
        var conversation = Transcripts.withoutInitialSystemMessage(transcript.messages());
        TransformMessages.apply(conversation, request.modelId(), apiName, request.model(),
                MistralToolCallIds.create())
            .stream().<Map<String, Object>>map(msg -> {
            var m = new HashMap<String, Object>();
            switch (msg) {
                case Message.UserMessage(var content, var ignoredTimestamp) -> {
                    var userMessage = userMessage(content, supportsImages);
                    if (userMessage != null) {
                        messages.add(userMessage);
                    }
                    return null; // 已自行落线（pi :805/:811/:814 三条分支各有去向）
                }
                case Message.AssistantMessage a -> {
                    m.put("role", "assistant");
                    // pi :822 —— 逐块净化后再拼（不是拼完再净化）。
                    m.put("content", extractSanitizedText(a.content()));
                }
                case Message.ToolResultMessage t -> {
                    // 具名模式而非组件解构：details/usage/addedToolNames 是结构化载荷，
                    // provider 投影只读 toolUseId + content（pi 的适配器同样不读它们）
                    m.put("role", "tool");
                    m.put("tool_call_id", t.toolUseId());
                    // pi :870 —— name 恒带（java 旧实现不发；原 docs/44 待裁决③ 整块照抄的顺带项）。
                    m.put("name", t.toolName());
                    m.put("content", toolContent(t, supportsImages));
                }
                // 包 A3（原 docs/51 §4.4）：中途系统消息落成**第二条** role=system 消息 ——
                // pi mistral-conversations.ts:787-790 对 `index === 0` 走
                // getSystemMessageText、其余走 renderSystemMessageUpdate，两者都 `continue`
                // 到同一条 role=system 落线。Mistral **没有**工具锚定机制
                // （MistralConversationsCompat 只有 supportsMidConvoSystemMessages 一个标志，
                // types.ts:849-852），所以这里只有文本。
                //
                // ⚠️ 下标 0 那一支在上面就落地了（`withoutInitialSystemMessage` 去掉了头），
                // 故走到这里的系统消息**必然**是中途的 ⇒ 判据不必再算一次。
                case Message.SystemMessage s -> {
                    var update = MessageTexts.renderSystemMessageUpdate(s);
                    if (!update.isEmpty()) {
                        var system = new HashMap<String, Object>();
                        system.put("role", "system");
                        system.put("content", SanitizeUnicode.surrogates(update));
                        messages.add(system);
                    }
                    return null; // 已自行落线
                }
            }
            return m;
        }).filter(java.util.Objects::nonNull).forEach(messages::add);
        return messages;
    }

    // ── 图片（包 H2，原 docs/44 步4）──────────────────────────────────────

    /**
     * user 消息落线 —— pi {@code mistral-conversations.ts:792-815}。无图片 ⇒ **串形态**（pi-java 的
     * 既有形状；pi 的串分支在 pi-java 结构上不可达，见 {@code 原 docs/44 §6}）；有图片 ⇒ 块数组。
     *
     * @return 该落的消息；{@code null} ＝ pi 的 {@code :814 continue}（整条消息不落线）
     */
    private static Map<String, Object> userMessage(List<ContentBlock> content,
                                                   boolean supportsImages) {
        boolean hadImages = content.stream().anyMatch(MistralContent::isImageBlock);
        if (!hadImages) {
            var m = new LinkedHashMap<String, Object>();
            m.put("role", "user");
            // pi :795/:802 —— java 的 user 恒为串形态 ⇒ 对齐 pi 的串分支（整串净化）。
            m.put("content", SanitizeUnicode.surrogates(extractText(content)));
            return m;
        }
        var chunks = new ArrayList<Map<String, Object>>();
        for (var block : content) {
            if (block instanceof ContentBlock.TextContent tc) {
                chunks.add(MistralContent.textChunk(
                    SanitizeUnicode.surrogates(tc.text())));
            } else if (block instanceof ContentBlock.ImageContent img) {
                chunks.add(MistralContent.imageChunk(
                    "data:" + img.mediaType() + ";base64," + img.data()));
            } else if (block instanceof ContentBlock.UrlImageContent url) {
                // java 扩展（pi 无此类型）：线格本名就是 image_url ⇒ 按它下发（原 docs/44 D4）。
                chunks.add(MistralContent.imageChunk(url.url()));
            }
        }
        if (!chunks.isEmpty()) {
            var m = new LinkedHashMap<String, Object>();
            m.put("role", "user");
            m.put("content", chunks);
            return m;
        }
        if (hadImages && !supportsImages) {
            // pi :809-811 —— ⚠️ 共享闸已先把图片换成文本块 ⇒ 此分支在两侧都不可达
            // （同 completions 车道的收集门，原 docs/44 §9）。照抄保留。
            var m = new LinkedHashMap<String, Object>();
            m.put("role", "user");
            m.put("content", "(image omitted: model does not support images)");
            return m;
        }
        return null; // pi :814 —— `continue`
    }

    /**
     * 工具结果的 content 块数组 —— pi {@code :851-874}：文本块在前（{@link MistralToolResultText#build}），
     * 图片块按 {@code supportsImages} 追加。
     */
    private static List<Map<String, Object>> toolContent(Message.ToolResultMessage tool,
                                                         boolean supportsImages) {
        // pi :852-854 —— **逐 part 净化再 join("\n")**（与 Anthropic 的 toolResult 口径相反）。
        var text = tool.content().stream()
                .filter(ContentBlock.TextContent.class::isInstance)
                .map(b -> SanitizeUnicode.surrogates(((ContentBlock.TextContent) b).text()))
                .collect(java.util.stream.Collectors.joining("\n"));
        boolean hasImages = tool.content().stream().anyMatch(MistralContent::isImageBlock);
        var chunks = new ArrayList<Map<String, Object>>();
        chunks.add(MistralContent.textChunk(
                MistralToolResultText.build(text, hasImages, supportsImages, tool.isError())));
        if (supportsImages) {
            for (var block : tool.content()) {
                if (block instanceof ContentBlock.ImageContent img) {
                    chunks.add(MistralContent.imageChunk(
                        "data:" + img.mediaType() + ";base64," + img.data()));
                } else if (block instanceof ContentBlock.UrlImageContent url) {
                    chunks.add(MistralContent.imageChunk(url.url()));
                }
            }
        }
        return chunks;
    }

    private static String extractText(List<ContentBlock> blocks) {
        return blocks.stream()
                .filter(c -> c instanceof ContentBlock.TextContent)
                .map(c -> ((ContentBlock.TextContent) c).text())
                .reduce("", String::concat);
    }

    /**
     * pi {@code mistral-conversations.ts:822/:853} 的逐块口径：**先净化每块再拼**
     * （不是拼完再净化 —— 跨块边界的孤高+孤低在 pi 会被各自删掉，拼完再净化则会让它们
     * 配对成活 emoji）。拼接方式沿用本车道既有的 {@code concat}（无分隔符），
     * 与 pi 的 {@code join("\n")} 的差别是本车道的既有偏差，不在本包范围内。
     */
    private static String extractSanitizedText(List<ContentBlock> blocks) {
        return blocks.stream()
                .filter(c -> c instanceof ContentBlock.TextContent)
                .map(c -> SanitizeUnicode.surrogates(((ContentBlock.TextContent) c).text()))
                .reduce("", String::concat);
    }
}
