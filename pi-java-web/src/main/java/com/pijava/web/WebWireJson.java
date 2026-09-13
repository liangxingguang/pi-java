package com.pijava.web;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

/**
 * Web 线格式转换：pi-java 持久化形状（{@code SessionJson.messageNode}）→
 * pi-webui 前端期望的 pi-ai 消息形状。持久化格式保持与 pi 对齐不动，仅
 * WS 载荷经此转换：{@code role:"tool"}→{@code toolResult}、
 * {@code toolUseId}→{@code toolCallId}、{@code tool_use}→{@code toolCall}、
 * thinking 块 {@code text}→{@code thinking} 字段。
 */
final class WebWireJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WebWireJson() {}

    /** 完整消息 → pi-webui wire 形状。 */
    static ObjectNode messageNode(Message m) {
        var node = MAPPER.createObjectNode();
        node.put("role", m instanceof Message.ToolResultMessage ? "toolResult" : m.role());
        var content = node.putArray("content");
        for (var block : m.content()) {
            content.add(blockNode(block));
        }
        if (m instanceof Message.ToolResultMessage tool) {
            node.put("toolCallId", tool.toolUseId());
            node.put("toolName", tool.toolName());
            // A7：结果消息的结构化载荷照 pi-ai 形状转发（pi ToolResultMessage 的
            // details/usage/addedToolNames，packages/ai/src/types.ts:452-468）。
            // undefined/null ⇒ 键缺席；空 addedToolNames ⇒ 键缺席（pi 的 length 门）。
            if (tool.details() != null) {
                node.set("details", MAPPER.valueToTree(tool.details()));
            }
            if (tool.usage() != null) {
                node.set("usage", MAPPER.valueToTree(tool.usage()));
            }
            if (!tool.addedToolNames().isEmpty()) {
                // 显式 ArrayNode：putPOJO 会留 POJONode，树内查询（测试、前端组装）
                // 在序列化前看不见元素
                var names = node.putArray("addedToolNames");
                for (var name : tool.addedToolNames()) {
                    names.add(name);
                }
            }
            node.put("isError", tool.isError());
        }
        return node;
    }

    /** 流式 partial {@code AssistantMessage} → pi-webui wire 形状。 */
    static ObjectNode assistantNode(AssistantMessage partial) {
        var node = MAPPER.createObjectNode();
        node.put("role", "assistant");
        var content = node.putArray("content");
        for (var block : partial.content()) {
            content.add(blockNode(block));
        }
        return node;
    }

    private static ObjectNode blockNode(ContentBlock block) {
        var node = MAPPER.createObjectNode();
        switch (block) {
            case ContentBlock.TextContent t -> {
                node.put("type", "text");
                node.put("text", t.text());
            }
            case ContentBlock.ThinkingContent t -> {
                node.put("type", "thinking");
                node.put("thinking", t.text());
            }
            case ContentBlock.ImageContent i -> {
                node.put("type", "image");
                node.put("mimeType", i.mediaType());
                node.put("data", i.data());
            }
            case ContentBlock.UrlImageContent u -> node.put("type", "image_url").put("url", u.url());
            case ContentBlock.ToolUseContent t -> {
                node.put("type", "toolCall");
                node.put("id", t.id());
                node.put("name", t.name());
                node.set("arguments", MAPPER.valueToTree(t.arguments()));
            }
            // 内层 tool_result 拍平为 text：前端 renderTool 只读 content[].text
            case ContentBlock.ToolResultContent t -> {
                node.put("type", "text");
                node.put("text", flattenText(t.content()));
            }
            case ContentBlock.DiffContent d -> {
                node.put("type", "diff");
                node.put("diffText", d.diffText());
            }
        }
        return node;
    }

    private static String flattenText(List<ContentBlock> blocks) {
        var sb = new StringBuilder();
        for (var b : blocks) {
            if (b instanceof ContentBlock.TextContent t) {
                sb.append(t.text());
            } else if (b instanceof ContentBlock.DiffContent d) {
                sb.append(d.diffText());
            } else if (b instanceof ContentBlock.ToolResultContent nested) {
                sb.append(flattenText(nested.content()));
            }
        }
        return sb.toString();
    }
}
