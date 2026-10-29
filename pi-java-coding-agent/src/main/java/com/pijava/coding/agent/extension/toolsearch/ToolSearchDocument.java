package com.pijava.coding.agent.extension.toolsearch;

import java.util.List;
import java.util.Map;

import com.pijava.agent.tool.ToolNamespace;
import com.pijava.coding.agent.extension.ExtensionToolInfo;

/**
 * pi {@code ToolSearchDocument}/{@code createToolSearchDocument}/{@code schemaText}
 * （tool.ts:24/:82-116 逐字）：索引文本＝工具名＋下划线展开名＋描述＋参数 schema
 * 的 description/属性名＋命名空间名/描述/说明。
 */
public record ToolSearchDocument(String name, String text) {

    /** pi {@code createToolSearchDocument}（:103-116 逐字）。 */
    public static ToolSearchDocument of(ExtensionToolInfo tool,
                                        ToolNamespace namespace) {
        var parts = new java.util.ArrayList<String>();
        parts.add(tool.name());
        parts.add(tool.name().replace("_", " "));
        parts.add(tool.description());
        schemaText(tool.parameters(), parts);
        if (namespace != null) {
            parts.add(namespace.name());
            parts.add(namespace.description() == null ? "" : namespace.description());
            parts.add(namespace.instructions() == null ? "" : namespace.instructions());
        }
        var text = new StringBuilder();
        for (var part : parts) {
            if (part != null && !part.trim().isEmpty()) {
                if (text.length() > 0) {
                    text.append(' ');
                }
                text.append(part);
            }
        }
        return new ToolSearchDocument(tool.name(), text.toString());
    }

    /** pi {@code schemaText}（:82-101 逐字）：递归收集 description 与属性名。 */
    @SuppressWarnings("unchecked")  // JSON schema 是 Map<String,Object> 树（Jackson 读形）
    private static void schemaText(Map<String, Object> schema, List<String> parts) {
        if (schema == null) {
            return;
        }
        if (schema.get("description") instanceof String description) {
            parts.add(description);
        }
        if (schema.get("properties") instanceof Map<?, ?> properties) {
            for (var entry : ((Map<String, Object>) properties).entrySet()) {
                parts.add(entry.getKey());
                if (entry.getValue() instanceof Map<?, ?> property) {
                    schemaText((Map<String, Object>) property, parts);
                }
            }
        }
        if (schema.get("items") instanceof Map<?, ?> items) {
            schemaText((Map<String, Object>) items, parts);
        }
        for (var key : List.of("anyOf", "oneOf", "allOf")) {
            if (schema.get(key) instanceof List<?> variants) {
                for (var variant : variants) {
                    if (variant instanceof Map<?, ?> map) {
                        schemaText((Map<String, Object>) map, parts);
                    }
                }
            }
        }
    }
}
