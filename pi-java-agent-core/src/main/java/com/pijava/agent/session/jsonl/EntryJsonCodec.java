package com.pijava.agent.session.jsonl;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.pijava.agent.entry.Entry;
import com.pijava.ai.message.Message;

/**
 * Decodes {@link Entry} subtypes from JSON nodes. The node carries the
 * {@code type} discriminant plus type-specific fields; identity fields may be
 * read from the node or supplied by the caller (SQLite payload decode).
 */
final class EntryJsonCodec {

    private EntryJsonCodec() {}

    static Entry decode(JsonNode node, String id, long seq, String parentId, Instant timestamp) {
        String type = JsonlCodec.requireString(node, "type");
        return switch (type) {
            case "message" -> {
                Message message = MessageJsonCodec.decode(node.get("message"));
                Boolean terminate = node.has("terminate") && node.get("terminate").asBoolean(false)
                    ? Boolean.TRUE : null;
                yield new Entry.Message(id, seq, parentId, timestamp, message, terminate);
            }
            case "model_change" -> new Entry.ModelChange(id, seq, parentId, timestamp,
                JsonlCodec.requireString(node, "provider"),
                JsonlCodec.requireString(node, "modelId"));
            case "thinking_level_change" -> new Entry.ThinkingLevelChange(id, seq, parentId, timestamp,
                JsonlCodec.requireString(node, "thinkingLevel"));
            case "active_tools_change" -> new Entry.ActiveToolsChange(id, seq, parentId, timestamp,
                stringList(node, "activeToolNames"));
            case "compaction" -> new Entry.Compaction(id, seq, parentId, timestamp,
                JsonlCodec.requireString(node, "summary"),
                MessageJsonCodec.decodeList(node.get("retainedTail")),
                JsonlCodec.requireInt(node, "tokensBefore"),
                JsonlCodec.optionalObject(node, "details"),
                decodeUsage(node.get("usage")));
            case "branch_summary" -> new Entry.BranchSummary(id, seq, parentId, timestamp,
                JsonlCodec.requireString(node, "fromId"),
                JsonlCodec.requireString(node, "summary"),
                JsonlCodec.optionalObject(node, "details"),
                decodeUsage(node.get("usage")));
            case "custom" -> new Entry.Custom(id, seq, parentId, timestamp,
                JsonlCodec.requireString(node, "customType"),
                JsonlCodec.optionalObject(node, "data"));
            default -> throw JsonlCodec.DecodeError.schema("has unknown entry type " + type);
        };
    }

    static List<String> stringList(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) {
            throw JsonlCodec.DecodeError.schema("has invalid " + field);
        }
        var list = new java.util.ArrayList<String>(value.size());
        for (var item : value) {
            if (!item.isTextual()) {
                throw JsonlCodec.DecodeError.schema("has invalid " + field);
            }
            list.add(item.textValue());
        }
        return list;
    }

    static com.pijava.ai.Usage decodeUsage(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw JsonlCodec.DecodeError.schema("has invalid usage");
        }
        JsonNode cost = node.get("cost");
        double costTotal = 0;
        double costInput = 0;
        double costOutput = 0;
        double costCacheRead = 0;
        double costCacheWrite = 0;
        if (cost != null && cost.isObject()) {
            costTotal = cost.has("total") ? cost.get("total").asDouble() : 0;
            costInput = cost.has("input") ? cost.get("input").asDouble() : 0;
            costOutput = cost.has("output") ? cost.get("output").asDouble() : 0;
            costCacheRead = cost.has("cacheRead") ? cost.get("cacheRead").asDouble() : 0;
            costCacheWrite = cost.has("cacheWrite") ? cost.get("cacheWrite").asDouble() : 0;
        }
        return new com.pijava.ai.Usage(
            node.has("input") ? node.get("input").asDouble() : 0,
            node.has("output") ? node.get("output").asDouble() : 0,
            node.has("cacheRead") ? node.get("cacheRead").asDouble() : 0,
            node.has("cacheWrite") ? node.get("cacheWrite").asDouble() : 0,
            node.has("cacheWrite1h") ? node.get("cacheWrite1h").asDouble() : null,
            node.has("reasoning") ? node.get("reasoning").asDouble() : null,
            node.has("totalTokens") ? node.get("totalTokens").asDouble() : 0,
            new com.pijava.ai.Usage.Cost(costInput, costOutput, costCacheRead, costCacheWrite, costTotal));
    }
}