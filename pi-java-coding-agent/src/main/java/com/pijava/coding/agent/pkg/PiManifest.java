package com.pijava.coding.agent.pkg;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import com.pijava.coding.agent.core.Json;

/**
 * pi {@code pi-manifest.ts}（全 35 行逐字对位）——读 package.json 的 {@code pi}
 * 字段：四资源键、每键须为全字符串数组（否则该键跳过）、非对象/解析失败
 * ⇒ {@code null}。
 */
public record PiManifest(
    List<String> extensions,
    List<String> skills,
    List<String> prompts,
    List<String> themes
) {

    public static PiManifest read(Path packageJsonPath) {
        try {
            var content = Files.readString(packageJsonPath);
            JsonNode pkg = Json.mapper().readTree(stripBom(content));
            if (pkg == null || !pkg.isObject()) {
                return null;
            }
            var pi = pkg.get("pi");
            if (pi == null || !pi.isObject()) {
                return null;
            }
            return new PiManifest(
                stringArrayOrNull(pi.get("extensions")),
                stringArrayOrNull(pi.get("skills")),
                stringArrayOrNull(pi.get("prompts")),
                stringArrayOrNull(pi.get("themes")));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** pi 逐键校验：{@code Array.isArray && every(string)}，否则该键缺席。 */
    private static List<String> stringArrayOrNull(JsonNode node) {
        if (node == null || !node.isArray()) {
            return null;
        }
        var entries = new ArrayList<String>();
        for (var element : node) {
            if (!element.isTextual()) {
                return null;
            }
            entries.add(element.asText());
        }
        return entries;
    }

    /** pi manifest 条目的资源键取数（null-safe）。 */
    public List<String> entriesOf(ResourceType type) {
        return switch (type) {
            case EXTENSIONS -> extensions;
            case SKILLS -> skills;
            case PROMPTS -> prompts;
            case THEMES -> themes;
        };
    }

    private static String stripBom(String content) {
        return content.startsWith("\uFEFF") ? content.substring(1) : content;
    }
}
