package com.pijava.ai.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pijava.ai.utils.ShortHash;

/**
 * 线程前缀哈希（pi {@code hashThreadPrefix}，{@code anthropic-messages.ts:321-350}）。
 *
 * <p>哈希覆盖「一条 {@code continue} 所依赖的全部内容」：端点（provider＋baseUrl）与随请求发送的
 * 固定设置（{@code model}／{@code system}／{@code tools}／{@code thinking}／{@code output_config}／
 * {@code tool_choice}／{@code betas}／{@code fallbacks}），以及服务端已持有的那些消息。</p>
 *
 * <p><b>消息归一化</b>（两处，漏一处就会每轮判「前缀变了」，静默退化成永远 {@code create}）：</p>
 * <ol>
 * <li>任意深度删除 {@code cache_control} —— 缓存标记会移动（pi 把标记挂到最新消息上），
 *     而它不改变消息语义；</li>
 * <li>字符串形式的 {@code content} 归一成 {@code [{"type":"text","text":…}]} —— 挂标记时
 *     最新用户消息会以文本块发送，下一次同样的消息可能是字符串。</li>
 * </ol>
 */
public final class AnthropicThreadHash {

    /** pi 逐字列出的设置字段顺序（顺序改了就与 pi 的哈希不同）。 */
    private static final String[] SETTINGS = {
        "model", "system", "tools", "thinking",
        "output_config", "tool_choice", "betas", "fallbacks"
    };

    private AnthropicThreadHash() {
    }

    /**
     * 计算前缀哈希。
     *
     * @param provider provider 名（pi {@code model.provider}）
     * @param baseUrl  有效端点（pi {@code model.baseUrl}）；null 视为空串
     * @param body     已构造的请求体 JSON（线程字段可不含）
     * @return {@link ShortHash} 的短哈希
     */
    public static String of(String provider, String baseUrl, JsonNode body) {
        var settings = JsonNodeFactory.instance.arrayNode();
        settings.add(provider == null ? "" : provider);
        settings.add(baseUrl == null ? "" : baseUrl);
        for (String key : SETTINGS) {
            settings.add(body == null ? NullNode.getInstance() : body.get(key));
        }
        JsonNode conversation = normalized(body == null ? null : body.get("messages"));
        return ShortHash.of(settings.toString() + conversation.toString());
    }

    /** 归一化后的消息数组（深拷贝，不改入参）。 */
    static JsonNode normalized(JsonNode messages) {
        return normalize(messages);
    }

    private static JsonNode normalize(JsonNode node) {
        if (node == null) {
            return NullNode.getInstance();
        }
        if (node.isObject()) {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            node.fields().forEachRemaining(field -> {
                if ("cache_control".equals(field.getKey())) {
                    return;
                }
                out.set(field.getKey(), normalize(field.getValue()));
            });
            JsonNode content = out.get("content");
            if (content != null && content.isTextual()) {
                ArrayNode blocks = out.arrayNode();
                ObjectNode block = out.objectNode();
                block.put("type", "text");
                block.put("text", content.asText());
                blocks.add(block);
                out.set("content", blocks);
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            node.forEach(item -> out.add(normalize(item)));
            return out;
        }
        return node;
    }
}
