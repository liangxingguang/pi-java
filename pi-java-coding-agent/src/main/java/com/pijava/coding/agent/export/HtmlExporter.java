package com.pijava.coding.agent.export;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 会话 → 自包含 HTML 导出（P6-12）。
 *
 * <p>读取 pi-java JSONL v4 会话文件（首行为 {@code kind:"header"}，后续为条目），
 * 渲染成单个文件的自包含 HTML：深色主题、消息气泡、Markdown 文本、思维块、
 * 可折叠工具卡片、图片，以及元数据条目（换模型/换思维级别/压缩等）。</p>
 */
public final class HtmlExporter {

    private static final DateTimeFormatter DATE = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private static final String CSS = """
        :root {
          --page: #18181e; --card: #1e1e24; --user: #343541;
          --accent: #8abeb7; --text: #d4d4d4; --muted: #808080;
          --error: #cc6666; --success: #b5bd68; --border: #505050;
          --code: #b5bd68; --heading: #f0c674;
        }
        * { box-sizing: border-box; }
        body { margin: 0; background: var(--page); color: var(--text);
               font: 15px/1.6 -apple-system, "Segoe UI", Roboto, sans-serif; }
        .page { max-width: 860px; margin: 0 auto; padding: 32px 20px 64px; }
        header.session { border-bottom: 1px solid var(--border); padding-bottom: 16px;
                         margin-bottom: 28px; }
        header.session h1 { font-size: 18px; margin: 0 0 6px; color: var(--heading); }
        header.session .meta { color: var(--muted); font-size: 13px; }
        .msg { display: flex; margin-bottom: 14px; }
        .msg.user { justify-content: flex-end; }
        .bubble { max-width: 84%; padding: 10px 14px; border-radius: 10px;
                  background: var(--card); }
        .msg.user .bubble { background: var(--user); }
        .msg.assistant .bubble { width: 100%; max-width: 100%; }
        .thinking { color: var(--muted); font-style: italic; margin-bottom: 8px; }
        .system-note { color: var(--muted); font-size: 13px; text-align: center;
                       margin: 10px 0; }
        details.tool { background: var(--card); border: 1px solid var(--border);
                       border-radius: 8px; margin: 8px 0; padding: 0 12px; }
        details.tool summary { cursor: pointer; padding: 8px 0; color: var(--accent); }
        details.tool.error summary { color: var(--error); }
        details.tool pre { margin: 0 0 10px; }
        pre { background: #0f0f14; border: 1px solid var(--border); border-radius: 8px;
              padding: 12px; overflow-x: auto; font: 13px/1.5 ui-monospace, Consolas, monospace; }
        code { font-family: ui-monospace, Consolas, monospace; }
        :not(pre) > code { background: var(--card); padding: 1px 5px; border-radius: 4px;
                           color: var(--code); }
        a { color: var(--accent); }
        blockquote { border-left: 3px solid var(--border); margin-left: 0; padding-left: 12px;
                     color: var(--muted); }
        hr { border: none; border-top: 1px solid var(--border); margin: 16px 0; }
        .msg img { max-width: 100%; border-radius: 8px; }
        .meta-line { color: var(--muted); font-size: 12px; text-align: center;
                     margin: 6px 0; }
        """;

    private final ObjectMapper json = new ObjectMapper();

    /** 导出到当前目录的 {@code pi-java-session-<basename>.html}。 */
    public Path export(Path sessionJsonl) {
        var fileName = sessionJsonl.getFileName();
        if (fileName == null) {
            throw new IllegalArgumentException("Not a file path: " + sessionJsonl);
        }
        var name = fileName.toString();
        var base = name.endsWith(".jsonl")
            ? name.substring(0, name.length() - 6) : name;
        return export(sessionJsonl, Path.of("pi-java-session-" + base + ".html"));
    }

    /** 导出到指定输出路径。 */
    public Path export(Path sessionJsonl, Path outputHtml) {
        try {
            var html = render(readLines(sessionJsonl));
            Files.writeString(outputHtml, html);
            return outputHtml;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ── JSONL 读取 ────────────────────────────────────────────────

    private JsonNode readLines(Path file) throws IOException {
        var lines = Files.readAllLines(file);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("Empty session file: " + file);
        }
        var header = json.readTree(lines.get(0));
        var entries = json.createArrayNode();
        for (int i = 1; i < lines.size(); i++) {
            var line = lines.get(i).trim();
            if (!line.isEmpty()) {
                entries.add(json.readTree(line));
            }
        }
        var root = json.createObjectNode();
        root.set("header", header);
        root.set("entries", entries);
        return root;
    }

    // ── 渲染 ──────────────────────────────────────────────────────

    private String render(JsonNode session) {
        var out = new StringBuilder();
        out.append("<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n")
            .append("  <meta charset=\"UTF-8\">\n")
            .append("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n")
            .append("  <title>pi-java session export</title>\n")
            .append("  <style>\n").append(CSS).append("\n  </style>\n")
            .append("</head>\n<body>\n  <div class=\"page\">\n");
        renderHeader(out, session.get("header"));
        var entries = session.get("entries");
        for (var entry : entries) {
            renderEntry(out, entry);
        }
        out.append("  </div>\n</body>\n</html>\n");
        return out.toString();
    }

    private void renderHeader(StringBuilder out, JsonNode header) {
        var id = text(header, "id");
        var cwd = text(header, "cwd");
        String when = null;
        if (header.hasNonNull("createdAtMs")) {
            when = DATE.format(Instant.ofEpochMilli(header.get("createdAtMs").asLong()));
        } else if (header.hasNonNull("timestamp")) {
            when = header.get("timestamp").asText();
        }
        out.append("    <header class=\"session\">\n")
            .append("      <h1>").append(escape(id)).append("</h1>\n")
            .append("      <div class=\"meta\">");
        if (when != null) {
            out.append(escape(when));
        }
        if (cwd != null && !cwd.isBlank()) {
            out.append(" · ").append(escape(cwd));
        }
        out.append("</div>\n    </header>\n");
    }

    private void renderEntry(StringBuilder out, JsonNode entry) {
        var type = text(entry, "type");
        switch (type) {
            case "message" -> renderMessage(out, entry.get("message"));
            case "model_change" -> meta(out, "model → " + text(entry, "provider")
                + "/" + text(entry, "modelId"));
            case "thinking_level_change" -> meta(out,
                "thinking → " + text(entry, "thinkingLevel"));
            case "active_tools_change" -> meta(out, "active tools → "
                + entry.get("activeToolNames"));
            case "compaction" -> meta(out, "compacted context (" + text(entry, "summary") + ")");
            case "branch_summary" -> meta(out, "branch summary: " + text(entry, "summary"));
            case "custom" -> meta(out, "custom entry: " + text(entry, "customType"));
            default -> { /* 未知类型不渲染 */ }
        }
    }

    private void renderMessage(StringBuilder out, JsonNode message) {
        if (message == null || !message.isObject()) {
            return;
        }
        var role = text(message, "role");
        switch (role) {
            case "user" -> {
                out.append("    <div class=\"msg user\"><div class=\"bubble\">\n");
                renderBlocks(out, message.get("content"));
                out.append("    </div></div>\n");
            }
            case "assistant" -> {
                out.append("    <div class=\"msg assistant\"><div class=\"bubble\">\n");
                renderBlocks(out, message.get("content"));
                out.append("    </div></div>\n");
            }
            case "tool" -> renderToolResult(out, message);
            case "system" -> meta(out, text(message, "content") == null
                ? "system message" : "system");
            default -> { /* 未知角色不渲染 */ }
        }
    }

    private void renderBlocks(StringBuilder out, JsonNode content) {
        if (content == null || !content.isArray()) {
            return;
        }
        for (var block : content) {
            var type = text(block, "type");
            switch (type) {
                case "text" -> out.append(MarkdownToHtml.render(text(block, "text")));
                case "thinking" -> out.append("      <div class=\"thinking\">")
                    .append(MarkdownToHtml.render(text(block, "text")))
                    .append("</div>\n");
                case "image" -> renderImage(out, block);
                case "image_url" -> {
                    var url = text(block, "url");
                    if (url != null) {
                        out.append("      <img src=\"").append(escape(url))
                            .append("\" alt=\"image\">\n");
                    }
                }
                case "tool_use" -> renderToolUse(out, block);
                case "tool_result" -> renderToolResult(out, block);
                default -> { /* 未知块类型不渲染 */ }
            }
        }
    }

    private void renderImage(StringBuilder out, JsonNode block) {
        var mediaType = text(block, "mediaType");
        var data = text(block, "data");
        if (data == null) {
            return;
        }
        var mime = mediaType == null ? "image/png" : mediaType;
        out.append("      <img src=\"data:").append(escape(mime))
            .append(";base64,").append(escape(data)).append("\" alt=\"image\">\n");
    }

    private void renderToolUse(StringBuilder out, JsonNode block) {
        var name = text(block, "name");
        var args = block.get("arguments");
        out.append("      <details class=\"tool\">\n")
            .append("        <summary>").append(escape(name)).append("</summary>\n");
        if (args != null) {
            out.append("        <pre>").append(escape(pretty(args)))
                .append("</pre>\n");
        }
        out.append("      </details>\n");
    }

    private void renderToolResult(StringBuilder out, JsonNode block) {
        var name = text(block, "toolName");
        var isError = block.has("isError") && block.get("isError").asBoolean(false);
        out.append("      <details class=\"tool").append(isError ? " error" : "").append("\">\n")
            .append("        <summary>").append(escape(name == null ? "tool" : name))
            .append("</summary>\n");
        var content = block.get("content");
        if (content != null && content.isArray()) {
            for (var item : content) {
                if ("text".equals(text(item, "type"))) {
                    out.append("        <pre>").append(escape(text(item, "text")))
                        .append("</pre>\n");
                }
            }
        } else if (content != null && content.isTextual()) {
            out.append("        <pre>").append(escape(content.asText())).append("</pre>\n");
        }
        out.append("      </details>\n");
    }

    private void meta(StringBuilder out, String message) {
        out.append("    <div class=\"meta-line\">").append(escape(message))
            .append("</div>\n");
    }

    private void meta(StringBuilder out, JsonNode node) {
        meta(out, node == null ? "" : node.toString());
    }

    private String pretty(JsonNode node) {
        try {
            return json.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (IOException e) {
            return node.toString();
        }
    }

    private static String text(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static String escape(String text) {
        return text == null ? "" : MarkdownToHtml.escape(text);
    }
}
