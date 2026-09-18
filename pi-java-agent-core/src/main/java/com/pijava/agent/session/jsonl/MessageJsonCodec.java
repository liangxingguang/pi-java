package com.pijava.agent.session.jsonl;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.pijava.agent.session.SessionJson;
import com.pijava.ai.Usage;
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
            default -> throw JsonlCodec.DecodeError.schema("has unknown message role");
        };
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
