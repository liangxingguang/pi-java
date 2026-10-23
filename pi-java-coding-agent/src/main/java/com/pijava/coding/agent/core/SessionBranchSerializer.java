package com.pijava.coding.agent.core;

import java.util.ArrayList;
import java.util.UUID;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pijava.agent.entry.Entry;
import com.pijava.agent.session.ContextEntries;
import com.pijava.agent.session.SessionJson;
import com.pijava.agent.session.jsonl.PiV3Wire;
import com.pijava.agent.session.jsonl.SessionWireShape;

/**
 * pi {@code serializeSessionBranch}（{@code core/session-export.ts:9-31}）＋
 * {@code createShareTrailingEntries}（{@code modes/interactive/session-share.ts:25-45}）
 * 对位：header（{@code type:"session"}）＋ branch 逐条 <b>{@code parentId} 重链</b>
 * （覆盖原 parentId，首条 null）＋ {@code pi.share} trailing 条目；逐行 JSON＋尾
 * {@code \n}。bug 上报包的 {@code session.jsonl} 生产者（批 15 T4）。
 */
public final class SessionBranchSerializer {

    private SessionBranchSerializer() {}

    /**
     * 序列化当前分支为 JSONL 文本（含 {@code pi.share} trailing 条目——
     * 携带 systemPrompt 与工具 schema，供上报包阅读者还原会话环境）。
     */
    public static String serialize(AgentSession session) {
        var timestamp = PiV3Wire.isoTimestamp(System.currentTimeMillis());
        var lines = new ArrayList<String>();

        // header（pi SessionHeader：type/version/id/timestamp/cwd）。
        var header = SessionJson.mapper().createObjectNode();
        header.put("type", "session");
        header.put("version", PiV3Wire.SESSION_VERSION);
        header.put("id", session.sessionId());
        header.put("timestamp", timestamp);
        header.put("cwd", session.cwd());
        lines.add(writeLine(header));

        // branch 逐条 parentId 重链（覆盖原 parentId，首条 null）。
        String parentId = null;
        var branch = ContextEntries.pathToLeaf(session.accumulatedEntries(),
            session.currentLeafId());
        for (var entry : branch) {
            var node = SessionWireShape.toPiTree(entry);
            node.remove("seq");
            node.put("timestamp", entry.timestamp() == null
                ? timestamp
                : PiV3Wire.isoTimestamp(entry.timestamp().toEpochMilli()));
            if (parentId != null) {
                node.put("parentId", parentId);
            } else {
                node.putNull("parentId");
            }
            parentId = entry.id();
            lines.add(writeLine(node));
        }

        lines.add(writeLine(shareTrailingEntry(session, parentId, timestamp)));
        return String.join("\n", lines) + "\n";
    }

    /**
     * pi {@code createShareTrailingEntries}：{@code customType:"pi.share"}、
     * {@code id} 8 位随机、{@code data:{systemPrompt, tools:[{name, description,
     * parameters}]}}。
     */
    private static ObjectNode shareTrailingEntry(
            AgentSession session, String parentId, String timestamp) {
        var node = SessionJson.mapper().createObjectNode();
        node.put("type", Entry.TYPE_CUSTOM);
        node.put("customType", "pi.share");
        // pi crypto.randomUUID().slice(0, 8)。
        node.put("id", UUID.randomUUID().toString().substring(0, 8));
        if (parentId != null) {
            node.put("parentId", parentId);
        } else {
            node.putNull("parentId");
        }
        node.put("timestamp", timestamp);
        var data = node.putObject("data");
        data.put("systemPrompt", session.harness().getSystemPrompt());
        var tools = data.putArray("tools");
        for (var tool : session.services().tools().all()) {
            var toolNode = tools.addObject();
            toolNode.put("name", tool.name());
            toolNode.put("description", tool.description());
            // pi tool.parameters ≙ AgentTool.inputSchema()（无对位时为空对象——
            // 本仓 inputSchema 恒有，故恒给）。
            toolNode.set("parameters",
                SessionJson.mapper().valueToTree(tool.inputSchema()));
        }
        return node;
    }

    private static String writeLine(ObjectNode node) {
        try {
            return SessionJson.mapper().writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize session branch line", e);
        }
    }
}
