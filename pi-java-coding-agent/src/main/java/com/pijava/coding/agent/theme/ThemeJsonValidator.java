package com.pijava.coding.agent.theme;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 主题 JSON 严格校验（pi {@code theme-schema.ts} 146 行的手写对位，批 21/docs/32
 * T5，裁决②）。
 *
 * <p>pi 用 typebox 且懒装（17MB 模块图）；java 用 Jackson 手写校验<b>恒装</b>——
 * 无加载成本，`setThemeJsonValidator` 缝不移植。严格语义逐字：顶层只收
 * {@code $schema/name/appearance/vars/colors/export}；{@code colors} 只收已知令牌，
 * 必填令牌必须齐；{@code export} 只收 {@code pageBg/cardBg/infoBg}；色值只收
 * 字符串或 0-255 整数。</p>
 *
 * <p>窄差 D125：逐条错误的 path/措辞与 typebox 引擎不同；缺失必填令牌清单与
 * 总体错误结构逐字。</p>
 */
public final class ThemeJsonValidator {

    private ThemeJsonValidator() {}

    /**
     * 校验一份主题文档。
     *
     * @param label 错误文案里的主题标识（pi 用路径或名字）
     * @param json 已解析的 JSON 树
     * @return 校验后的 {@link ThemeJson}
     * @throws IllegalArgumentException 校验失败（文案与 pi 同构）
     */
    public static ThemeJson validate(String label, JsonNode json) {
        // pi theme-schema.ts:98-108：名字含 "/" 快检先于 schema。
        if (json != null && json.isObject() && json.get("name") != null
                && json.get("name").isTextual() && json.get("name").asText().contains("/")) {
            throw new IllegalArgumentException(
                "Invalid theme name \"" + json.get("name").asText() + "\": theme names cannot contain"
                    + " \"/\" because it is reserved for automatic light/dark theme settings.");
        }

        var missingColors = new TreeSet<String>();
        var otherErrors = new ArrayList<String>();

        if (json == null || !json.isObject()) {
            otherErrors.add("/: expected an object");
            throw new IllegalArgumentException(assemble(label, missingColors, otherErrors));
        }

        var allowed = java.util.Set.of("$schema", "name", "appearance", "vars", "colors", "export");
        var fields = json.properties().iterator();
        while (fields.hasNext()) {
            var field = fields.next();
            if (!allowed.contains(field.getKey())) {
                otherErrors.add("/: unexpected property \"" + field.getKey() + "\"");
            }
        }

        // name：必填字符串（"/" 已在快检拦截）。
        var nameNode = json.get("name");
        if (nameNode == null || !nameNode.isTextual()) {
            otherErrors.add("/name: expected string");
        }

        // appearance：可选 "dark"|"light"。
        ThemeAppearance appearance = null;
        var appearanceNode = json.get("appearance");
        if (appearanceNode != null) {
            if (appearanceNode.isTextual() && ThemeAppearance.fromPiName(appearanceNode.asText()) != null) {
                appearance = ThemeAppearance.fromPiName(appearanceNode.asText());
            } else {
                otherErrors.add("/appearance: expected \"dark\" or \"light\"");
            }
        }

        // vars：可选 record<string, colorValue>。
        var vars = new LinkedHashMap<String, Object>();
        var varsNode = json.get("vars");
        if (varsNode != null) {
            if (!varsNode.isObject()) {
                otherErrors.add("/vars: expected object");
            } else {
                for (var entry : varsNode.properties()) {
                    var value = colorValue(entry.getValue());
                    if (value == null) {
                        otherErrors.add("/vars/" + entry.getKey()
                            + ": expected string or integer 0-255");
                    } else {
                        vars.put(entry.getKey(), value);
                    }
                }
            }
        }

        // colors：必填对象；键 ⊆ 已知令牌；必填令牌齐全；值 = colorValue。
        var colors = new LinkedHashMap<String, Object>();
        var colorsNode = json.get("colors");
        if (colorsNode == null || !colorsNode.isObject()) {
            otherErrors.add("/colors: expected an object with theme color tokens");
        } else {
            for (var entry : colorsNode.properties()) {
                var token = ThemeToken.fromPiName(entry.getKey());
                if (token == null) {
                    otherErrors.add("/colors: unexpected token \"" + entry.getKey() + "\"");
                    continue;
                }
                var value = colorValue(entry.getValue());
                if (value == null) {
                    otherErrors.add("/colors/" + entry.getKey()
                        + ": expected string or integer 0-255");
                } else {
                    colors.put(entry.getKey(), value);
                }
            }
            for (var token : ThemeToken.values()) {
                if (token.fallback() == null && !colorsNode.has(token.piName())) {
                    missingColors.add(token.piName());
                }
            }
        }

        // export：可选对象，键 ⊆ {pageBg, cardBg, infoBg}。
        ThemeJson.ExportColors exportColors = null;
        var exportNode = json.get("export");
        if (exportNode != null) {
            if (!exportNode.isObject()) {
                otherErrors.add("/export: expected object");
            } else {
                for (var key : exportNode.properties().stream().map(java.util.Map.Entry::getKey).toList()) {
                    if (!key.equals("pageBg") && !key.equals("cardBg") && !key.equals("infoBg")) {
                        otherErrors.add("/export: unexpected property \"" + key + "\"");
                    }
                }
                exportColors = new ThemeJson.ExportColors(
                    exportColorValue(exportNode.get("pageBg"), "/export/pageBg", otherErrors),
                    exportColorValue(exportNode.get("cardBg"), "/export/cardBg", otherErrors),
                    exportColorValue(exportNode.get("infoBg"), "/export/infoBg", otherErrors));
            }
        }

        if (!missingColors.isEmpty() || !otherErrors.isEmpty()) {
            throw new IllegalArgumentException(assemble(label, missingColors, otherErrors));
        }
        // LinkedHashMap 保 pi 的插入序（Map.copyOf 键序不定——批 20 排障钉同族）。
        return new ThemeJson(nameNode.asText(), appearance,
            java.util.Collections.unmodifiableMap(new LinkedHashMap<>(vars)),
            java.util.Collections.unmodifiableMap(new LinkedHashMap<>(colors)), exportColors);
    }

    /** pi 错误组装（theme-schema.ts:128-141 逐字结构）。 */
    private static String assemble(String label, TreeSet<String> missingColors,
                                   List<String> otherErrors) {
        var message = new StringBuilder("Invalid theme \"" + label + "\":\n");
        if (!missingColors.isEmpty()) {
            message.append("\nMissing required color tokens:\n");
            for (var color : missingColors) {
                message.append("  - ").append(color).append('\n');
            }
            message.setLength(message.length() - 1);
            message.append("\n\nPlease add these colors to your theme's \"colors\" object.");
            message.append("\nSee the built-in themes (dark.json, light.json) for reference values.");
        }
        if (!otherErrors.isEmpty()) {
            message.append("\n\nOther errors:\n");
            message.append(String.join("\n", otherErrors.stream()
                .map(error -> error.startsWith("  - ") ? error : "  - " + error).toList()));
        }
        return message.toString();
    }

    /** 色值联合：字符串（任意，语义后查）或 0-255 整数；其余 ⇒ null。 */
    private static Object colorValue(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isIntegralNumber()) {
            int value = node.intValue();
            if (value >= 0 && value <= 255) {
                return value;
            }
        }
        return null;
    }

    private static Object exportColorValue(JsonNode node, String path, List<String> otherErrors) {
        if (node == null) {
            return null;
        }
        var value = colorValue(node);
        if (value == null) {
            otherErrors.add(path + ": expected string or integer 0-255");
        }
        return value;
    }
}
