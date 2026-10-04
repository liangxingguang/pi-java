package com.pijava.ai.protocol;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.openai.core.ObjectMappers;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionTool;

import com.pijava.ai.catalog.CacheControlFormat;
import com.pijava.ai.catalog.CacheRetention;
import com.pijava.ai.catalog.ModelCompat;

/**
 * completions 线上的 anthropic 形状缓存断点（包 A-02／B105）—— pi
 * {@code openai-completions.ts:1069-1180} 的六个函数整体落在这里
 * （{@code OpenAICompletionsMessageConverter} 已超 500 行，缓存面不再往里堆）。
 *
 * <p><b>为什么是树改写而不是类型化 builder</b>：pi 对**建好的**消息/工具数组做就地变异
 * （{@code :858-859}），而 openai-java 的参数对象不可变、且 {@code cache_control} 不在
 * SDK 的类型面上（{@code ChatCompletionContentPartText} 没有这个字段）。树改写
 * （{@code valueToTree → set → treeToValue}）是 {@code kimiToolSystemMessage:295-305}
 * 已验证的通路（{@code SdkJsonEscapeHatchTest} 钉过逐字节往返），并且让「倒序 walk、
 * 挂不上就往前找」的语义与 pi 的变异循环 1:1。</p>
 *
 * <p>⚠️ 与 anthropic 车道（A-01）的**两处刻意不同**，都有 pi 行号背书：</p>
 * <ol>
 *   <li>工具断点**没有** {@code supportsCacheControlOnTools} 门 —— 那道门在
 *       {@code anthropic-messages.ts:1114}，completions 侧的
 *       {@code addCacheControlToLastTool:1118-1129} 是裸挂（原 docs/59 §4.9）。</li>
 *   <li>断点形状只有 {@code {type:"ephemeral",ttl?}} 一种（{@code :1069-1079}），
 *       落点是文本分片（串 content 先改写成单分片数组），不是 anthropic 的任意块类型。</li>
 * </ol>
 */
final class CompletionsCacheControl {

    private CompletionsCacheControl() {}

    /** SDK 自己的 mapper（未知键直通的关键；与 kimiToolSystemMessage 同源）。 */
    private static final ObjectMapper JSON = ObjectMappers.jsonMapper();

    /**
     * pi {@code getCompatCacheControl:1069-1079}：{@code cacheControlFormat !== "anthropic"}
     * 或 {@code cacheRetention === "none"} ⇒ 无断点；long 且支持长缓存 ⇒ 带 {@code ttl:"1h"}。
     *
     * @param compat    本车道**解析后**的 compat（探测已合入，:1632/:1671-1677）
     * @param retention 三源合并后的保留期（选项 ?? 环境变量 ?? short）
     * @return 断点对象（键序 type→ttl，pi 的展开序）；缺席 ≙ pi 的 {@code undefined}
     */
    static Optional<Map<String, String>> cacheControlOf(ModelCompat compat,
                                                        CacheRetention retention) {
        if (compat.cacheControlFormat() != CacheControlFormat.ANTHROPIC
                || retention == CacheRetention.NONE) {
            return Optional.empty();
        }
        var cc = new LinkedHashMap<String, String>();
        cc.put("type", "ephemeral");
        if (retention == CacheRetention.LONG
                && Boolean.TRUE.equals(compat.supportsLongCacheRetention())) {
            cc.put("ttl", "1h");
        }
        return Optional.of(cc);
    }

    /**
     * pi {@code applyAnthropicCacheControl:1081-1089}：三断点，次序照抄
     * （system → 工具末项 → 倒序会话消息）。列表元素被**原位替换**成挂好断点的副本。
     */
    static void apply(List<ChatCompletionMessageParam> messages,
                      List<ChatCompletionTool> tools,
                      Map<String, String> cacheControl) {
        addCacheControlToSystemPrompt(messages, cacheControl);
        addCacheControlToLastTool(tools, cacheControl);
        addCacheControlToLastConversationMessage(messages, cacheControl);
    }

    /**
     * pi {@code addCacheControlToSystemPrompt:1091-1101}：第一条 system|developer 消息。
     * ⚠️ 挂不上（空 content）也**return** —— pi 就是这么写的（不继续找下一条指令消息），
     * 照抄不「修好」。
     */
    private static void addCacheControlToSystemPrompt(List<ChatCompletionMessageParam> messages,
                                                      Map<String, String> cacheControl) {
        for (int i = 0; i < messages.size(); i++) {
            var message = messages.get(i);
            if (message.isSystem() || message.isDeveloper()) {
                attachToMessage(messages, i, cacheControl);
                return;
            }
        }
    }

    /** pi {@code addCacheControlToLastConversationMessage:1103-1116}：倒序，挂上即停。 */
    private static void addCacheControlToLastConversationMessage(
            List<ChatCompletionMessageParam> messages, Map<String, String> cacheControl) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            var message = messages.get(i);
            if (message.isUser() || message.isAssistant() || message.isTool()) {
                if (attachToMessage(messages, i, cacheControl)) {
                    return;
                }
            }
        }
    }

    /**
     * pi {@code addCacheControlToLastTool:1118-1129}：工具表末项（⚠️ 无
     * {@code supportsCacheControlOnTools} 门 —— 见类 javadoc）。
     */
    private static void addCacheControlToLastTool(List<ChatCompletionTool> tools,
                                                  Map<String, String> cacheControl) {
        if (tools == null || tools.isEmpty()) {
            return;
        }
        var last = tools.size() - 1;
        var node = (ObjectNode) JSON.valueToTree(tools.get(last));
        node.set("cache_control", JSON.valueToTree(cacheControl));
        tools.set(last, rewrite(node, ChatCompletionTool.class));
    }

    /**
     * pi {@code addCacheControlToTextContent:1152-1180}：串 content 非空 ⇒ 改写成
     * {@code [{type:"text",text,cache_control}]}；数组 content ⇒ 挂在**最后一个**
     * {@code type:"text"} 分片上；content 缺席/空串/无文本分片 ⇒ {@code false}
     * （调用方继续往前找）。
     */
    private static boolean attachToMessage(List<ChatCompletionMessageParam> messages,
                                           int index, Map<String, String> cacheControl) {
        var node = (ObjectNode) JSON.valueToTree(messages.get(index));
        var content = node.get("content");
        if (content == null || content.isNull()) {
            return false;
        }
        if (content.isTextual()) {
            if (content.asText().isEmpty()) {
                return false;                       // pi :1161-1163
            }
            var part = JSON.createObjectNode();
            part.put("type", "text");
            part.put("text", content.asText());
            part.set("cache_control", JSON.valueToTree(cacheControl));
            node.set("content", JSON.createArrayNode().add(part));   // pi :1164-1171
        } else if (content.isArray()) {
            JsonNode target = null;
            for (int i = content.size() - 1; i >= 0; i--) {          // pi :1177-1184
                var part = content.get(i);
                if (part.isObject() && "text".equals(part.path("type").asText())) {
                    target = part;
                    break;
                }
            }
            if (target == null) {
                return false;
            }
            ((ObjectNode) target).set("cache_control", JSON.valueToTree(cacheControl));
        } else {
            return false;
        }
        messages.set(index, rewrite(node, ChatCompletionMessageParam.class));
        return true;
    }

    private static <T> T rewrite(ObjectNode node, Class<T> type) {
        try {
            return JSON.treeToValue(node, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot re-attach cache_control to " + type.getSimpleName(), e);
        }
    }
}
