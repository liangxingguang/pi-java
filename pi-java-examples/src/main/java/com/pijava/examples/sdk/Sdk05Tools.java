package com.pijava.examples.sdk;

import java.util.List;

/**
 * pi {@code examples/sdk/05-tools.ts} 等价改写（docs/56 §2.1）——
 * 四组工具名选择 + cwd 变体（形状差 D247）。
 */
public final class Sdk05Tools {

    public record ToolSession(String cwd, List<String> tools, String message) {}

    /** pi L16-47 四个 createAgentSession 配置投影。 */
    public static List<ToolSession> sessions(String customCwd) {
        return List.of(
            new ToolSession(null, List.of("read", "grep", "find", "ls"),
                "Read-only session created"),
            new ToolSession(null, List.of("read", "bash", "grep"),
                "Custom tools session created"),
            new ToolSession(customCwd, List.of("read", "bash", "edit", "write"),
                "Custom cwd session created"),
            new ToolSession(customCwd, List.of("read", "bash", "grep"),
                "Specific tools with custom cwd session created"));
    }

    public static final String CUSTOM_CWD = "/path/to/project";
}
