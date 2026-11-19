package com.pijava.examples.sdk;

import java.util.ArrayList;
import java.util.List;

/**
 * pi {@code examples/sdk/07-context-files.ts} 等价改写（docs/56 §2.1）——
 * agentsFilesOverride 追加虚拟 AGENTS.md，getAgentsFiles 枚举（形状差 D247）。
 */
public final class Sdk07ContextFiles {

    public record AgentsFile(String path, String content) {}

    /** pi L23-28 虚拟 AGENTS.md 逐字。 */
    public static final String VIRTUAL_AGENTS = String.join("\n",
        "# Project Guidelines",
        "",
        "## Code Style",
        "- Use TypeScript strict mode",
        "- No any types",
        "- Prefer const over let");

    /** pi L347：保留 current 并追加虚拟文件。 */
    public static List<AgentsFile> agentsFilesOverride(List<AgentsFile> current) {
        var result = new ArrayList<>(current);
        result.add(new AgentsFile("/virtual/AGENTS.md", VIRTUAL_AGENTS));
        return result;
    }

    /** pi L37-46 输出逐字。 */
    public static List<String> describe(List<AgentsFile> discovered) {
        var lines = new ArrayList<String>();
        lines.add("Discovered context files:");
        for (var f : discovered) {
            lines.add("  - " + f.path() + " (" + f.content().length() + " chars)");
        }
        lines.add("Session created with " + (discovered.size() + 1) + " context files");
        return lines;
    }
}
