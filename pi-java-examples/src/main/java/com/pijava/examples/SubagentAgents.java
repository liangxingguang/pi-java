package com.pijava.examples;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * pi {@code examples/extensions/subagent/agents.ts:1-157} 移植（docs/54 §2.3）——
 * agent 定义发现与 frontmatter 解析（纯逻辑）。parseFrontmatter 在宿主为 package-private
 * （D15），此处复刻四键标量解析。
 */
public final class SubagentAgents {

    public enum AgentScope { USER, PROJECT, BOTH }

    public record AgentConfig(String name, String description, List<String> tools,
                              String model, String source, String systemPrompt) {}

    /** pi L4：逗号串→List；非数组/非字符串→空。 */
    static List<String> parseToolList(Object raw) {
        if (raw instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        if (raw instanceof String s && !s.isBlank()) {
            return java.util.Arrays.stream(s.split(",")).map(String::trim)
                .filter(x -> !x.isEmpty()).toList();
        }
        return List.of();
    }

    /** 最小 YAML frontmatter：仅 name/description/tools/model 四个标量键。 */
    static Map<String, Object> parseFrontmatter(String content) {
        var result = new LinkedHashMap<String, Object>();
        var lines = content.split("\n", -1);
        if (lines.length == 0 || !lines[0].trim().equals("---")) {
            return result;
        }
        for (int i = 1; i < lines.length; i++) {
            var line = lines[i];
            if (line.trim().equals("---")) {
                break;
            }
            var colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            var key = line.substring(0, colon).trim();
            if (!List.of("name", "description", "tools", "model").contains(key)) {
                continue;
            }
            var value = line.substring(colon + 1).trim();
            if (value.startsWith("[") && value.endsWith("]")) {
                var inner = value.substring(1, value.length() - 1);
                var list = new ArrayList<String>();
                for (var part : inner.split(",")) {
                    var v = part.trim().replaceAll("^['\"]|['\"]$", "");
                    if (!v.isEmpty()) {
                        list.add(v);
                    }
                }
                result.put(key, list);
            } else {
                result.put(key, value.replaceAll("^['\"]|['\"]$", ""));
            }
        }
        return result;
    }

    /** 从 .md 文件加载一个 agent（systemPrompt 为 frontmatter 之后的正文）。 */
    static AgentConfig loadAgent(Path file, String source) throws IOException {
        var content = Files.readString(file, StandardCharsets.UTF_8);
        var fm = parseFrontmatter(content);
        var name = fm.get("name") instanceof String s ? s : null;
        var description = fm.get("description") instanceof String s ? s : null;
        if (name == null || description == null) {
            return null;
        }
        var bodyStart = content.indexOf("---", content.indexOf("---") + 3);
        var systemPrompt = bodyStart < 0 ? "" : content.substring(bodyStart + 3).trim();
        return new AgentConfig(name, description, parseToolList(fm.get("tools")),
            fm.get("model") instanceof String s2 ? s2 : null, source, systemPrompt);
    }

    static List<AgentConfig> loadAgentsFromDir(Path dir, String source) {
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        var result = new ArrayList<AgentConfig>();
        try (var stream = Files.list(dir)) {
            for (var file : stream.filter(p -> p.toString().endsWith(".md")).sorted().toList()) {
                var agent = loadAgent(file, source);
                if (agent != null) {
                    result.add(agent);
                }
            }
        } catch (IOException ignored) {
        }
        return result;
    }

    /** 从 cwd 向上查找最近的 `<configDir>/agents` 目录。 */
    static Path findNearestProjectAgentsDir(Path cwd, String configDirName) {
        var current = cwd.toAbsolutePath().normalize();
        while (current != null) {
            var candidate = current.resolve(configDirName).resolve("agents");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        return null;
    }

    /** pi discoverAgents：user 目录 + project 目录（project 覆盖 user，按 name 去重）。 */
    static List<AgentConfig> discoverAgents(AgentScope scope, Path userDir,
            Path projectDir) {
        var byName = new LinkedHashMap<String, AgentConfig>();
        if (scope == AgentScope.USER || scope == AgentScope.BOTH) {
            for (var a : loadAgentsFromDir(userDir, "user")) {
                byName.put(a.name(), a);
            }
        }
        if (scope == AgentScope.PROJECT || scope == AgentScope.BOTH) {
            for (var a : loadAgentsFromDir(projectDir, "project")) {
                byName.put(a.name(), a); // project 覆盖 user
            }
        }
        return List.copyOf(byName.values());
    }

    /** pi L151-156：空→none，否则 `name (source): description` 以 "; " 连接。 */
    static String formatAgentList(List<AgentConfig> agents) {
        if (agents.isEmpty()) {
            return "none";
        }
        var parts = new ArrayList<String>();
        for (var a : agents) {
            parts.add(a.name() + " (" + a.source() + "): " + a.description());
        }
        return String.join("; ", parts);
    }
}
