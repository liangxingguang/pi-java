package com.pijava.agent.session.jsonl;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
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
            case "system" -> new Message.SystemMessage(content);
            case "user" -> new Message.UserMessage(content);
            case "assistant" -> new Message.AssistantMessage(
                content,
                JsonlCodec.optionalString(node, "stopReason"),
                decodeDeferred(node.get("deferred")));
            case "tool" -> new Message.ToolResultMessage(
                JsonlCodec.requireString(node, "toolUseId"),
                JsonlCodec.requireString(node, "toolName"),
                content,
                node.has("isError") && node.get("isError").asBoolean(false));
            default -> throw JsonlCodec.DecodeError.schema("has unknown message role");
        };
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
            case "thinking" -> new ContentBlock.ThinkingContent(JsonlCodec.requireString(node, "text"));
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
