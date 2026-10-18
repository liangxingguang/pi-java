package com.pijava.coding.agent.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * pi {@code toolPatternRegExp}/{@code createToolNameMatcher}/{@code isMcpToolName}
 * （core/mcp-servers.ts:11-42，B236/docs/11 §1.3）。
 *
 * <p>模式规则只有一条：{@code *} → 任意字符；其余字符全部字面化，全串锚定
 * （<b>不是</b> minimatch——ai 层 ModelGlob 那套不适用于工具名）。</p>
 */
public final class ToolNameMatcher {

    /** pi {@code MCP_RESOURCE_TOOLS}（mcp-servers.ts:33-37）——资源三件套也算 MCP 工具名。 */
    private static final Set<String> MCP_RESOURCE_TOOLS = Set.of(
        "list_mcp_resources", "list_mcp_resource_templates", "read_mcp_resource");

    private ToolNameMatcher() {}

    /** pi {@code toolPatternRegExp}（:11-18）。{@code Pattern.quote} 全字面化 ≙ pi 的正则元字符转义。 */
    static Pattern toolPattern(String pattern) {
        var parts = pattern.split("\\*", -1);
        var source = new StringBuilder("^");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                source.append(".*");
            }
            if (!parts[i].isEmpty()) {
                source.append(Pattern.quote(parts[i]));
            }
        }
        return Pattern.compile(source.append("$").toString());
    }

    /** pi {@code createToolNameMatcher}（:21-26）：精确名集合 + 模式列表，任一命中即真。 */
    public static Predicate<String> create(List<String> entries) {
        Set<String> names = new HashSet<>();
        List<Pattern> patterns = new ArrayList<>();
        for (var entry : entries) {
            if (entry.contains("*")) {
                patterns.add(toolPattern(entry));
            } else {
                names.add(entry);
            }
        }
        return name -> names.contains(name)
            || patterns.stream().anyMatch(p -> p.matcher(name).matches());
    }

    /** pi {@code isMcpToolName}（:40-42）。 */
    public static boolean isMcpToolName(String name) {
        return name.startsWith("mcp__") || MCP_RESOURCE_TOOLS.contains(name);
    }
}
