package com.pijava.agent.session.jsonl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.pijava.agent.entry.Entry;
import com.pijava.agent.session.SessionJson;

/**
 * Lay an entry out as a <b>pi-mainstream JSON tree</b> (package 14, {@code docs/14 §4.2}).
 *
 * <p>The internal records serialize to the local wire dialect; this is the
 * single shape point that converts three spellings so pi can read sessions
 * directly. Both JSONL ({@link PiV3Wire#encodeEntryLine}) and the SQLite
 * backend ({@code EntryRows.entryPayload}) go through here.</p>
 *
 * <table>
 * <caption>Three remaps, deep-walked</caption>
 * <tr><th>local dialect</th><th>pi mainstream</th></tr>
 * <tr><td>message {@code "role":"tool"}</td><td>{@code "role":"toolResult"}</td></tr>
 * <tr><td>key {@code toolUseId}</td><td>{@code toolCallId}</td></tr>
 * <tr><td>block {@code "type":"tool_use"}</td><td>{@code "type":"toolCall"}</td></tr>
 * </table>
 *
 * <p>Rebuilds objects field by field in original order (Jackson ObjectNodes
 * use an ordered map, but {@code put} moves a key to the end). Everything
 * else — nested arrays, scalar values, other keys — passes through
 * untouched. Internal model and record component names never change.</p>
 */
public final class SessionWireShape {

    private SessionWireShape() {}

    /** Serialize {@code entry} and convert it to pi spellings; the identity fields stay intact. */
    public static ObjectNode toPiTree(Entry entry) {
        JsonNode node = SessionJson.mapper().valueToTree(entry);
        if (!(node instanceof ObjectNode object)) {
            throw new IllegalStateException("entry serialized to " + node.getNodeType());
        }
        return (ObjectNode) remap(object);
    }

    private static JsonNode remap(JsonNode node) {
        if (node.isObject()) {
            var result = SessionJson.mapper().createObjectNode();
            node.fields().forEachRemaining(field -> result.set(
                remapKey(field.getKey(), field.getValue()),
                remap(remapValue(field.getKey(), field.getValue()))));
            return result;
        }
        if (node.isArray()) {
            var result = SessionJson.mapper().createArrayNode();
            for (JsonNode child : node) {
                result.add(remap(child));
            }
            return result;
        }
        return node;
    }

    private static String remapKey(String key, JsonNode value) {
        return "toolUseId".equals(key) ? "toolCallId" : key;
    }

    private static JsonNode remapValue(String key, JsonNode value) {
        if ("role".equals(key) && value.isTextual() && "tool".equals(value.textValue())) {
            return TextNode.valueOf("toolResult");
        }
        if ("type".equals(key) && value.isTextual() && "tool_use".equals(value.textValue())) {
            return TextNode.valueOf("toolCall");
        }
        return value;
    }
}
