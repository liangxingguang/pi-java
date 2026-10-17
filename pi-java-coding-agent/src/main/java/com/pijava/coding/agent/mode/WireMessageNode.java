package com.pijava.coding.agent.mode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.pijava.ai.message.Message;

/**
 * 一条消息的线格式投影（批 2 自 {@code JsonEventMapper} 纯搬移拆分，无行为变 —
 * 原文件加五个新帧会破 500 行红线）。所有方法以调用方的 {@link ObjectMapper}
 * 为参（它带着 {@code StreamEvent}/{@code Message} mixin 与 Instant 模块）。
 *
 * <p>pi 的线上形状就是 {@code JSON.stringify(message)}，而消息对象**自带**
 * {@code role} 与 {@code timestamp}（{@code ai/src/types.ts:417-470} 三个
 * interface 各自声明）。pi-java 的 record 组件里没有这两样 —— {@code role()} 是
 * **接口方法**（Jackson 不把它当属性），时间戳在 {@code Entry} 上、不在消息上。</p>
 *
 * <p><b>为什么做节点级投影而不是改 {@code ContentBlock} 的注解</b>（§8.0 裁决 C 的
 * 待核结果）：{@code SessionJson} 只注册了 {@code ContentBlock} 的
 * <b>serializer</b>、<b>没有 deserializer</b> ⇒ <b>读既有会话文件靠的正是注解里的
 * 判别名</b>（{@code tool_use} 等）。改注解会让旧会话文件读不出来。
 * 故只在<b>线这一层</b>改名。</p>
 */
final class WireMessageNode {

    private WireMessageNode() {}

    /** 一条消息的线格式（包⑩，{@code 原 docs/37}）。 */
    static ObjectNode messageNode(ObjectMapper mapper, Message m) {
        var node = mapper.createObjectNode();
        node.put("role", m instanceof Message.ToolResultMessage ? "toolResult" : m.role());
        var raw = (ObjectNode) mapper.valueToTree(m);
        var fields = raw.fieldNames();
        while (fields.hasNext()) {
            var name = fields.next();
            // 工具结果：pi 的字段名是 toolCallId，Java 的 record 组件名是 toolUseId。
            var wireName = "toolUseId".equals(name) ? "toolCallId" : name;
            if ("content".equals(name)) {
                node.set(name, contentArray(mapper, raw.get(name)));
            } else if (isAbsentOptional(name, raw.get(name))) {
                // pi 的 addedToolNames?/details?/usage? 缺席即省略。
                continue;
            } else {
                node.set(wireName, raw.get(name));
            }
        }
        return node;
    }

    /** pi 的可选键：缺席（null / 空数组）就该省略，而不是写一个空值。 */
    private static boolean isAbsentOptional(String name, JsonNode value) {
        return ("addedToolNames".equals(name) && value.isArray() && value.isEmpty())
            || value.isNull();
    }

    /**
     * 内容块数组的线格式：改判别字面量与字段名（{@code tool_use}→{@code toolCall}、
     * thinking 的 {@code text}→{@code thinking}），并按 pi 的可选语义省掉
     * {@code signature}/{@code redacted} 的缺席值。
     */
    private static JsonNode contentArray(ObjectMapper mapper, JsonNode content) {
        if (!content.isArray()) {
            return content;
        }
        var out = mapper.createArrayNode();
        for (var block : content) {
            out.add(blockNodeForWire(mapper, (ObjectNode) block));
        }
        return out;
    }

    private static ObjectNode blockNodeForWire(ObjectMapper mapper, ObjectNode block) {
        var node = mapper.createObjectNode();
        var type = block.path("type").asText("");
        switch (type) {
            case "tool_use" -> node.put("type", "toolCall");
            case "thinking" -> {
                node.put("type", "thinking");
                // pi 的 ThinkingContent 是 {type, thinking, signature?, redacted?}。
                var text = block.path("text");
                node.set("thinking", text);
                if (!block.path("signature").asText("").isEmpty()) {
                    node.set("signature", block.get("signature"));
                }
                if (block.path("redacted").asBoolean(false)) {
                    node.put("redacted", true);
                }
                return node;
            }
            default -> node.put("type", type);
        }
        var fields = block.fieldNames();
        while (fields.hasNext()) {
            var name = fields.next();
            if (!"type".equals(name)) {
                node.set(name, block.get(name));
            }
        }
        return node;
    }
}
