package com.pijava.coding.agent.rpc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.pijava.agent.entry.Entry;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.WireJson;
import com.pijava.coding.agent.export.HtmlExporter;

/**
 * RPC 导出族＋共享 JSON mapper（批 29＝docs/41 结构债清空批，自 {@link RpcDispatcher}
 * 纯移动拆出；行级注释逐字随迁）。
 *
 * <p>无状态工具类：{@code AgentSession} 一律入参（dispatcher 的 session 字段会被
 * new_session/fork/clone 重绑）。</p>
 */
final class RpcExportSupport {

    /**
     * in-memory 会话（{@code --no-session}）的 {@code export_html}/{@code export_jsonl}
     * 会**内联**序列化条目（{@link #entryWithRole}）⇒ 带上身份缝挂的 {@code Instant}。
     * 此前这里是**裸** ObjectMapper ⇒ {@code export_html} 抛（台账 B52）。同形止血见
     * {@link WireJson}。
     */
    private static final ObjectMapper JSON = new ObjectMapper()
        .registerModule(WireJson.instantAsEpochMillis());

    private RpcExportSupport() {
    }

    /** 共享 mapper（handleLine 的行解析与 get_state 的 RpcModel 序列化同用）。 */
    static ObjectMapper mapper() {
        return JSON;
    }

    /** 导出当前会话为 HTML（P6-12），返回生成的文件路径。 */
    static String exportHtml(AgentSession session, String outputPath) {
        try {
            var tmp = Files.createTempFile("pi-java-export", ".jsonl");
            writeSessionJsonl(session, tmp);
            var html = outputPath == null || outputPath.isBlank()
                ? new HtmlExporter().export(tmp)
                : new HtmlExporter().export(tmp, java.nio.file.Path.of(outputPath));
            return html.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 写会话 JSONL：持久化会话走 {@code exportJsonl}；in-memory 会话内联写
     *  header + transcript（HtmlExporter 兼容格式）。 */
    private static void writeSessionJsonl(AgentSession session, java.nio.file.Path target)
            throws IOException {
        try {
            session.exportJsonl(target);
            return;
        } catch (IllegalStateException e) {
            // in-memory 会话（--no-session）：无持久化会话，内联序列化
        }
        var header = JSON.createObjectNode();
        header.put("id", session.sessionName());
        header.put("name", session.sessionName());
        var lines = new ArrayList<String>();
        lines.add(JSON.writeValueAsString(header));
        for (var entry : RpcSessionPayloads.harnessTranscript(session)) {
            lines.add(JSON.writeValueAsString(entryWithRole(entry)));
        }
        Files.write(target, lines);
    }

    /** Entry JSON 补 message.role（HtmlExporter 按 role 渲染；Message.role() 派生不序列化）。 */
    private static ObjectNode entryWithRole(Entry entry) {
        var node = (ObjectNode) JSON.valueToTree(entry);
        if (entry instanceof Entry.Message m && node.get("message") instanceof ObjectNode msg) {
            msg.put("role", m.message().role());
        }
        return node;
    }

    static String extractField(String line, String field) {
        try {
            JsonNode node = JSON.readTree(line);
            JsonNode value = node.get(field);
            return value == null || value.isNull() ? null : value.asText();
        } catch (Exception e) {
            return null;
        }
    }
}
