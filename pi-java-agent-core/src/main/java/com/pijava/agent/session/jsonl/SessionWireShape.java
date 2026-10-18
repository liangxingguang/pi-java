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
        if (entry instanceof Entry.BashExecution be) {
            return bashExecutionTree(be);
        }
        JsonNode node = SessionJson.mapper().valueToTree(entry);
        if (!(node instanceof ObjectNode object)) {
            throw new IllegalStateException("entry serialized to " + node.getNodeType());
        }
        return (ObjectNode) remap(object);
    }

    /**
     * pi 的 bashExecution 寄居在 {@code type:"message"} 条目里
     * （{@code session-manager.ts:1204-1214}），{@code message.timestamp} 是 epoch 毫秒
     * （{@code Date.now()}，{@code messages.ts:39}）；可选键缺席省略（NON_NULL 惯例）。
     * {@code Entry.BashExecution} 故意不在 {@code @JsonSubTypes} 里（线判别共享
     * {@code "message"}），所以这棵树手工拼（docs/25 §3.3 ②）。
     */
    private static ObjectNode bashExecutionTree(Entry.BashExecution be) {
        var mapper = SessionJson.mapper();
        var root = mapper.createObjectNode();
        root.put("type", Entry.TYPE_MESSAGE);
        root.put("id", be.id());
        root.put("seq", be.seq());
        if (be.parentId() != null) {
            root.put("parentId", be.parentId());
        } else {
            root.putNull("parentId");
        }
        if (be.timestamp() != null) {
            root.put("timestamp", PiV3Wire.isoTimestamp(be.timestamp().toEpochMilli()));
        }
        var msg = root.putObject("message");
        msg.put("role", "bashExecution");
        msg.put("command", be.command());
        msg.put("output", be.output());
        if (be.exitCode() != null) {
            msg.put("exitCode", be.exitCode().intValue());
        }
        msg.put("cancelled", be.cancelled());
        msg.put("truncated", be.truncated());
        if (be.fullOutputPath() != null) {
            msg.put("fullOutputPath", be.fullOutputPath());
        }
        if (be.timestamp() != null) {
            msg.put("timestamp", be.timestamp().toEpochMilli());
        }
        if (be.excludeFromContext() != null) {
            msg.put("excludeFromContext", be.excludeFromContext().booleanValue());
        }
        return root;
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
