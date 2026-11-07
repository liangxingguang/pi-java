package com.pijava.coding.agent.export;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * L5 W8 拆件（docs/04 结构债惯例）：{@link HtmlExporter} 的系统消息渲染簇——
 * pi {@code export-html/template.js:324-386/:1404-1420} 逐字。
 *
 * <p>分支上<b>首条</b>系统条目渲染完整提示（{@code content}＋{@code sections}），
 * 后续条目渲染 update 文案（{@code Removed}/{@code Updated system prompt section
 * "<name>"}，附工具增删摘要）；编号锚点 {@code entry-<id>} 与消息同源。
 * 自 {@code HtmlExporter} 纯移动以守住文件 ≤500 行闸门。</p>
 */
final class HtmlSystemMessages {

    private HtmlSystemMessages() {
    }

    /**
     * 系统条目 HTML（空 ⇒ 返回空串、不产容器）。
     *
     * @param entry        条目节点（读 {@code id}/{@code parentId}/{@code timestamp}）
     * @param message      条目内的 system 消息
     * @param leading      分支首条判定（{@code HtmlExporter#isLeadingSystemEntry}）
     */
    static String render(JsonNode entry, JsonNode message, boolean leading) {
        var body = new StringBuilder();
        if (leading) {
            var prompt = systemMessageText(message);
            if (!prompt.isEmpty()) {
                body.append(systemPromptBlock("System Prompt", prompt));
            }
            body.append(toolsList("Available Tools", message.get("toolsAdded")));
        } else {
            var update = renderSystemMessageUpdate(message);
            var changes = describeSystemUpdate(message);
            if (!update.isEmpty()) {
                body.append(systemPromptBlock(
                    "System Prompt Update" + (changes.isEmpty() ? "" : ": " + changes), update));
            }
            body.append(toolsList("Tools Added", message.get("toolsAdded")));
            body.append(toolsList("Tools Removed", message.get("toolsRemoved")));
        }
        if (body.length() == 0) {
            return "";
        }
        var id = text(entry, "id");
        return "    <div class=\"system-entry\""
            + (id == null ? "" : " id=\"entry-" + escape(id) + "\"")
            + ">\n      <div class=\"message-timestamp\">"
            + escape(timestampOf(entry, message)) + "</div>\n"
            + body + "    </div>\n";
    }

    /** 时间戳：条目优先，其次消息自身（pi {@code tsHtml} 两源同义）。 */
    private static String timestampOf(JsonNode entry, JsonNode message) {
        var value = text(entry, "timestamp");
        if (value == null) {
            value = text(message, "timestamp");
        }
        return value == null ? "" : value;
    }

    /** pi {@code getSystemMessageText}（template.js:332-338）。 */
    static String systemMessageText(JsonNode message) {
        var parts = new java.util.ArrayList<String>();
        parts.add(systemContentText(message.get("content")));
        var sections = message.get("sections");
        if (sections != null && sections.isObject()) {
            for (var value : sections) {
                if (!value.isNull()) {
                    parts.add(value.asText());
                }
            }
        }
        return parts.stream().filter(part -> !part.isEmpty())
            .collect(java.util.stream.Collectors.joining("\n\n"));
    }

    /** pi {@code renderSystemMessageUpdate}（template.js:341-350）。 */
    static String renderSystemMessageUpdate(JsonNode message) {
        var parts = new java.util.ArrayList<String>();
        var text = systemContentText(message.get("content"));
        if (!text.isEmpty()) {
            parts.add(text);
        }
        var sections = message.get("sections");
        if (sections != null && sections.isObject()) {
            sections.properties().forEach(section -> parts.add(section.getValue().isNull()
                ? "Removed system prompt section \"" + section.getKey() + "\"."
                : "Updated system prompt section \"" + section.getKey() + "\":\n\n"
                    + section.getValue().asText()));
        }
        return String.join("\n\n", parts);
    }

    /** pi {@code describeSystemUpdate}（template.js:368-386）：短摘要。 */
    static String describeSystemUpdate(JsonNode message) {
        var parts = new java.util.ArrayList<String>();
        if (!systemContentText(message.get("content")).isEmpty()) {
            parts.add("instructions");
        }
        var sections = message.get("sections");
        if (sections != null && sections.isObject()) {
            sections.properties().forEach(section ->
                parts.add(section.getValue().isNull() ? "-" + section.getKey() : section.getKey()));
        }
        var toolChanges = new java.util.ArrayList<String>();
        var added = message.get("toolsAdded");
        if (added != null && added.isArray()) {
            for (var tool : added) {
                toolChanges.add("+" + text(tool, "name"));
            }
        }
        var removed = message.get("toolsRemoved");
        if (removed != null && removed.isArray()) {
            for (var tool : removed) {
                toolChanges.add("-" + text(tool, "name"));
            }
        }
        if (!toolChanges.isEmpty()) {
            parts.add("tools " + String.join(" ", toolChanges));
        }
        return String.join(", ", parts);
    }

    /** pi {@code systemContentText}（template.js:324-330）。 */
    private static String systemContentText(JsonNode content) {
        if (content == null) {
            return "";
        }
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            var texts = new java.util.ArrayList<String>();
            for (var block : content) {
                if ("text".equals(text(block, "type"))) {
                    var value = text(block, "text");
                    if (value != null && !value.isEmpty()) {
                        texts.add(value);
                    }
                }
            }
            return String.join("\n", texts);
        }
        return "";
    }

    /** pi {@code renderSystemPromptBlock}（template.js:1556-1573）：超过 10 行折叠。 */
    private static String systemPromptBlock(String title, String prompt) {
        var lines = prompt.split("\n", -1);
        var header = "      <div class=\"system-prompt-header\">" + escape(title)
            + "</div>\n";
        if (lines.length > 10) {
            var preview = String.join("\n", java.util.Arrays.copyOf(lines, 10));
            return "      <div class=\"system-prompt\">\n" + header
                + "        <div class=\"system-prompt-preview\">" + escape(preview) + "</div>\n"
                + "        <div class=\"system-prompt-expand-hint\">... ("
                + (lines.length - 10) + " more lines)</div>\n"
                + "      </div>\n";
        }
        return "      <div class=\"system-prompt\">\n" + header
            + "        <div class=\"system-prompt-full\">" + escape(prompt) + "</div>\n"
            + "      </div>\n";
    }

    /** pi {@code renderToolsList}（template.js:1575-1602）：无参工具一行、有参列属性。 */
    static String toolsList(String title, JsonNode tools) {
        if (tools == null || !tools.isArray() || tools.isEmpty()) {
            return "";
        }
        var body = new StringBuilder("      <div class=\"tools-list\">\n")
            .append("        <div class=\"tools-header\">").append(escape(title))
            .append("</div>\n        <div class=\"tools-content\">\n");
        for (var tool : tools) {
            body.append("          <div class=\"tool-item\"><span class=\"tool-item-name\">")
                .append(escape(text(tool, "name")))
                .append("</span> - <span class=\"tool-item-desc\">")
                .append(escape(text(tool, "description")))
                .append("</span>");
            appendToolParams(body, tool.get("parameters"));
            body.append("</div>\n");
        }
        return body.append("        </div>\n      </div>\n").toString();
    }

    private static void appendToolParams(StringBuilder body, JsonNode parameters) {
        var properties = parameters == null ? null : parameters.get("properties");
        if (properties == null || !properties.isObject() || properties.isEmpty()) {
            return;
        }
        var required = parameters.get("required");
        body.append("\n            <div class=\"tool-params-content\">\n");
        properties.properties().forEach(entry -> {
            var isRequired = required != null && required.isArray()
                && java.util.stream.StreamSupport.stream(required.spliterator(), false)
                    .anyMatch(node -> entry.getKey().equals(node.asText()));
            var type = text(entry.getValue(), "type");
            body.append("              <div class=\"tool-param\">")
                .append(escape(entry.getKey())).append(' ')
                .append(escape(type == null ? "any" : type)).append(' ')
                .append(isRequired ? "required" : "optional");
            var description = text(entry.getValue(), "description");
            if (description != null) {
                body.append(" <span class=\"tool-param-desc\">")
                    .append(escape(description)).append("</span>");
            }
            body.append("</div>\n");
        });
        body.append("            </div>");
    }

    private static String text(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    static String escape(String text) {
        return text == null ? "" : MarkdownToHtml.escape(text);
    }
}
