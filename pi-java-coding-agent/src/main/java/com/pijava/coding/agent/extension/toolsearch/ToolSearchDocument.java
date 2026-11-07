package com.pijava.coding.agent.extension.toolsearch;

import java.util.List;
import java.util.Map;

import com.pijava.agent.tool.ToolNamespace;
import com.pijava.coding.agent.extension.ExtensionToolInfo;

/**
 * pi {@code ToolSearchDocument}/{@code createToolSearchDocument}/{@code schemaText}
 * （tool.ts:24-31/:103-129 逐字）：{@code text} 只含描述与 schema 文本；
 * {@code namespaceText} 单独承载命名空间的名/描述/说明；<b>名称不再进 text</b>
 * （排序器从 {@code name} 字段读）。
 *
 * @param name         工具名
 * @param text         描述＋schema 的描述与属性名
 * @param namespaceText 命名空间名＋描述＋说明（无命名空间 ⇒ 空串）
 */
public record ToolSearchDocument(String name, String text, String namespaceText) {

    /** 三参旧形状（无命名空间）。 */
    public ToolSearchDocument(String name, String text) {
        this(name, text, "");
    }

    /** pi {@code createToolSearchDocument}（:119-129 逐字）。 */
    public static ToolSearchDocument of(ExtensionToolInfo tool, ToolNamespace namespace) {
        var parts = new java.util.ArrayList<String>();
        parts.add(tool.description());
        schemaText(tool.parameters(), parts);
        var text = joinNonBlank(parts);
        var namespaceText = namespace == null ? "" : joinNonBlank(List.of(
            namespace.name(),
            namespace.description() == null ? "" : namespace.description(),
            namespace.instructions() == null ? "" : namespace.instructions()));
        return new ToolSearchDocument(tool.name(), text, namespaceText);
    }

    /** pi 的 {@code parts.filter((part) => part.trim()).join(" ")}。 */
    private static String joinNonBlank(List<String> parts) {
        var out = new StringBuilder();
        for (var part : parts) {
            if (part == null || part.trim().isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(part);
        }
        return out.toString();
    }

    /** pi {@code schemaText}（:107-125 逐字）：递归收集 description 与属性名。 */
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
