package com.pijava.coding.agent.extension;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarFile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 扩展清单，供 {@code pi-java package list} 展示（P6-16）。
 *
 * <p>从 JAR 内 {@code META-INF/pi-extension.json} 读取；缺失时返回 empty（由
 * {@code package list} 从已加载实例反射推导）。</p>
 */
public record ExtensionManifest(
    String name,
    String version,
    String description,
    List<String> tools,
    List<String> commands,
    List<String> providers,
    List<String> skills
) {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 从 JAR 读取清单；无 {@code META-INF/pi-extension.json} 返回 empty。 */
    public static Optional<ExtensionManifest> from(Path jar) {
        try (var jf = new JarFile(jar.toFile())) {
            var entry = jf.getJarEntry("META-INF/pi-extension.json");
            if (entry == null) {
                return Optional.empty();
            }
            JsonNode node = JSON.readTree(jf.getInputStream(entry));
            return Optional.of(new ExtensionManifest(
                text(node, "name"),
                text(node, "version"),
                text(node, "description"),
                strings(node, "tools"),
                strings(node, "commands"),
                strings(node, "providers"),
                strings(node, "skills")));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static List<String> strings(JsonNode node, String field) {
        if (!node.has(field) || !node.get(field).isArray()) {
            return List.of();
        }
        var out = new java.util.ArrayList<String>();
        node.get(field).forEach(v -> out.add(v.asText()));
        return List.copyOf(out);
    }
}
