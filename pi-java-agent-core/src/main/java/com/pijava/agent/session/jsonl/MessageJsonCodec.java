package com.pijava.agent.session.jsonl;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.pijava.agent.session.SessionJson;
import com.pijava.ai.Usage;
import com.pijava.ai.api.ToolDefinition;
import com.pijava.ai.api.ToolReference;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.DeferredHandle;
import com.pijava.ai.message.Message;

/**
 * Decodes {@link Message}/{@link ContentBlock} JSON trees. Encoding is
 * handled by the shared {@link com.pijava.agent.session.SessionJson} mapper.
 */
final class MessageJsonCodec {

    private MessageJsonCodec() {}

    static List<Message> decodeList(JsonNode node) {
        if (node == null || !node.isArray()) {
            throw JsonlCodec.DecodeError.schema("has invalid message list");
        }
        var messages = new ArrayList<Message>(node.size());
        for (var item : node) {
            messages.add(decode(item));
        }
        return messages;
    }

    static Message decode(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw JsonlCodec.DecodeError.schema("has invalid message");
        }
        String role = JsonlCodec.requireString(node, "role");
        List<ContentBlock> content = decodeBlocks(node.get("content"));
        return switch (role) {
            case "user" -> new Message.UserMessage(content);
            case "assistant" -> new Message.AssistantMessage(
                content,
                JsonlCodec.optionalString(node, "stopReason"),
                decodeDeferred(node.get("deferred")),
                JsonlCodec.optionalString(node, "api"),
                JsonlCodec.optionalString(node, "provider"),
                JsonlCodec.optionalString(node, "model"),
                decodeUsage(node.get("usage")),
                decodeTimestamp(node.get("timestamp")),
                JsonlCodec.optionalString(node, "errorMessage"),
                JsonlCodec.optionalString(node, "rawStopReason"));
            case "tool" -> new Message.ToolResultMessage(
                JsonlCodec.requireString(node, "toolUseId"),
                JsonlCodec.requireString(node, "toolName"),
                content,
                JsonlCodec.optionalAny(node, "details"),
                JsonlCodec.optionalAny(node, "usage"),
                decodeStringList(node.get("addedToolNames")),
                node.has("isError") && node.get("isError").asBoolean(false));
            case "system" -> new Message.SystemMessage(
                content,
                decodeTimestamp(node.get("timestamp")),
                decodeSections(node.get("sections")),
                decodeToolsAdded(node.get("toolsAdded")),
                decodeToolReferences(node.get("toolsRemoved")));
            default -> throw JsonlCodec.DecodeError.schema("has unknown message role");
        };
    }

    /**
     * 系统消息的 {@code sections} —— pi 是 {@code Record<string, string | null>}
     * （{@code types.ts:501}）：文本值是「设成这段」，**{@code null} 是「删掉这段」**
     * （{@code utils/transcript.ts:81-83}）。
     *
     * <p>用 {@code LinkedHashMap} 是**语义**不是口味：插入序就是渲染序（pi 的
     * {@code Object.entries}，{@code utils/text.ts:17}），包 A2 的 F3 已经把写侧改成保序
     * 副本；读侧不保序的话 round-trip 会静默重排 prompt 的段落。
     * {@link Message.SystemMessage} 的紧凑构造器会再拷一次，同样保序。</p>
     *
     * <p>JSON {@code null} 直接收下（包 A4 的形状裁决，{@code docs/52 §4.1}）——
     * B87a 当时对它抛错是因为删除态还没有载体，那个载体现在有了。
     * 其余非文本类型（数字／对象／数组）仍然抛：把它们读成删除或读成空串都会**静默改 prompt**。</p>
     */
    private static Map<String, String> decodeSections(JsonNode node) {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw JsonlCodec.DecodeError.schema("has invalid sections");
        }
        var sections = new LinkedHashMap<String, String>();
        node.fields().forEachRemaining(entry -> {
            if (entry.getValue().isNull()) {
                sections.put(entry.getKey(), null);
                return;
            }
            if (!entry.getValue().isTextual()) {
                throw JsonlCodec.DecodeError.schema(
                    "has non-string section " + entry.getKey());
            }
            sections.put(entry.getKey(), entry.getValue().textValue());
        });
        return sections;
    }

    /**
     * 系统消息的 {@code toolsAdded} —— **两种形状都读**：pi 的 ai 层 {@code Tool}
     * （三键 {@code {name, description, parameters}}，{@code types.ts:600-605}）与
     * A1 落线用的 {@code ToolDefinition} 全形（schema 键名是 {@code inputSchema}）。
     *
     * <p>读侧两种、写侧一种（包 B87b 之后只写 pi 形状）是**刻意**的：盘上已有的会话带的
     * 是旧形，读不了等于把历史会话弄丢。</p>
     *
     * <p>A1 的四个元数据键（{@code label}/{@code promptSnippet}/{@code promptGuidelines}/
     * {@code renderShell}）**不回读** —— pi 的 {@code toolsAdded} 是 ai 层 {@code Tool}，
     * 本就不带它们（{@code docs/50 §10 L-F}）；三参便捷构造器给出与 pi 同义的缺省。</p>
     */
    private static List<ToolDefinition> decodeToolsAdded(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw JsonlCodec.DecodeError.schema("has invalid toolsAdded");
        }
        var tools = new ArrayList<ToolDefinition>(node.size());
        for (var item : node) {
            if (!item.isObject()) {
                throw JsonlCodec.DecodeError.schema("has invalid toolsAdded entry");
            }
            Map<String, Object> schema = JsonlCodec.optionalObject(item, "parameters");
            if (schema == null) {
                schema = JsonlCodec.optionalObject(item, "inputSchema");
            }
            if (schema == null) {
                throw JsonlCodec.DecodeError.schema("has invalid toolsAdded entry");
            }
            tools.add(new ToolDefinition(JsonlCodec.requireString(item, "name"),
                JsonlCodec.optionalString(item, "description"), schema));
        }
        return List.copyOf(tools);
    }

    /** 系统消息的 {@code toolsRemoved} —— pi 的 {@code ToolReference} 是 {@code {name}}（{@code types.ts:607-609}）。 */
    private static List<ToolReference> decodeToolReferences(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw JsonlCodec.DecodeError.schema("has invalid toolsRemoved");
        }
        var references = new ArrayList<ToolReference>(node.size());
        for (var item : node) {
            if (!item.isObject()) {
                throw JsonlCodec.DecodeError.schema("has invalid toolsRemoved entry");
            }
            references.add(new ToolReference(JsonlCodec.requireString(item, "name")));
        }
        return List.copyOf(references);
    }

    /** {@code addedToolNames}：pi 只在非空时写出；缺席/非数组空表 ⇒ 空列表（缺省）。 */
    private static List<String> decodeStringList(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw JsonlCodec.DecodeError.schema("has invalid addedToolNames");
        }
        var names = new ArrayList<String>(node.size());
        for (var item : node) {
            if (!item.isTextual()) {
                throw JsonlCodec.DecodeError.schema("has invalid addedToolNames entry");
            }
            names.add(item.textValue());
        }
        return List.copyOf(names);
    }

    private static DeferredHandle decodeDeferred(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw JsonlCodec.DecodeError.schema("has invalid deferred handle");
        }
        return new DeferredHandle(
            JsonlCodec.requireString(node, "provider"),
            JsonlCodec.requireString(node, "modelId"),
            JsonlCodec.requireString(node, "api"),
            JsonlCodec.requireString(node, "id"),
            JsonlCodec.optionalLong(node, "expiresAt"),
            JsonlCodec.optionalLong(node, "pollAfterMs"),
            JsonlCodec.optionalObject(node, "data"));
    }

    /** 3a：assistant 消息的 token 计量（pi 必有字段；旧文件缺席 ⇒ null，键省略）。 */
    private static Usage decodeUsage(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw JsonlCodec.DecodeError.schema("has invalid usage");
        }
        try {
            return SessionJson.mapper().treeToValue(node, Usage.class);
        } catch (Exception e) {
            throw JsonlCodec.DecodeError.schema("has invalid usage");
        }
    }

    /** pi {@code timestamp: number}（epoch ms）→ Instant；缺席 ⇒ null。 */
    private static Instant decodeTimestamp(JsonNode timestampNode) {
        if (timestampNode == null || timestampNode.isNull()) {
            return null;
        }
        if (!timestampNode.isNumber()) {
            throw JsonlCodec.DecodeError.schema("has invalid timestamp");
        }
        return Instant.ofEpochMilli(timestampNode.asLong());
    }

    /**
     * Read a thinking block's text field. New key {@code thinking} wins; the
     * legacy key {@code text} is the fallback.
     *
     * <p>pi names the field {@code thinking} ({@code types.ts:358}) and persists
     * entries verbatim ({@code session-manager.ts:1030-1056}), so {@code thinking}
     * is the shape that matches pi byte-for-byte. pi-java wrote {@code text} until
     * this change (docs/31 §8.33 P6) — sessions already on disk in
     * {@code ~/.pi-java} still carry it, so both must be accepted.</p>
     */
    private static String thinkingText(JsonNode node) {
        JsonNode current = node.get("thinking");
        if (current != null && !current.isNull()) {
            return JsonlCodec.requireString(node, "thinking");
        }
        return JsonlCodec.requireString(node, "text");
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    static List<ContentBlock> decodeBlocks(JsonNode node) {
        if (node == null || !node.isArray()) {
            throw JsonlCodec.DecodeError.schema("has invalid content");
        }
        var blocks = new ArrayList<ContentBlock>(node.size());
        for (var item : node) {
            blocks.add(decodeBlock(item));
        }
        return blocks;
    }

    static ContentBlock decodeBlock(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw JsonlCodec.DecodeError.schema("has invalid content block");
        }
        String type = JsonlCodec.requireString(node, "type");
        return switch (type) {
            case "text" -> new ContentBlock.TextContent(JsonlCodec.requireString(node, "text"));
            case "thinking" -> new ContentBlock.ThinkingContent(
                thinkingText(node),
                nullToEmpty(JsonlCodec.optionalString(node, "thinkingSignature")),
                JsonlCodec.optionalBoolean(node, "redacted"));
            case "image" -> new ContentBlock.ImageContent(
                JsonlCodec.requireString(node, "mediaType"),
                JsonlCodec.requireString(node, "data"));
            case "image_url" -> new ContentBlock.UrlImageContent(
                JsonlCodec.requireString(node, "url"));
            case "tool_use" -> new ContentBlock.ToolUseContent(
                JsonlCodec.requireString(node, "id"),
                JsonlCodec.requireString(node, "name"),
                JsonlCodec.optionalObject(node, "arguments"));
            case "tool_result" -> new ContentBlock.ToolResultContent(
                JsonlCodec.requireString(node, "toolUseId"),
                JsonlCodec.requireString(node, "toolName"),
                decodeBlocks(node.get("content")),
                node.has("isError") && node.get("isError").asBoolean(false));
            case "diff" -> new ContentBlock.DiffContent(
                JsonlCodec.requireString(node, "diffText"));
            default -> throw JsonlCodec.DecodeError.schema("has unknown content block type");
        };
    }
}
