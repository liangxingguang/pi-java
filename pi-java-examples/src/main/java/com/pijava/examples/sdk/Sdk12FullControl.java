package com.pijava.examples.sdk;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** pi {@code examples/sdk/12-full-control.ts} 等价改写（docs/56 §2.2）——9 参数显式配置+prompt。 */
public final class Sdk12FullControl {

    public static final String PROMPT = "List files in the current directory.";
    public static final String SYSTEM_PROMPT =
        "You are a minimal assistant.\nAvailable: read, bash. Be concise.";

    /** pi L51-61 九参数显式配置投影。 */
    public static Map<String, Object> config(String cwd) {
        var m = new LinkedHashMap<String, Object>();
        m.put("cwd", cwd);
        m.put("agentDir", "/tmp/my-agent");
        m.put("model", "anthropic/claude-sonnet-4-5");
        m.put("thinkingLevel", "off");
        m.put("authPath", "/tmp/my-agent/auth.json");
        m.put("modelsPath", "/tmp/my-agent/models.json");
        m.put("tools", List.of("read", "bash"));
        m.put("systemPrompt", SYSTEM_PROMPT);
        return m;
    }

    /** pi L36-49 手写 ResourceLoader 全空集合投影。 */
    public static Map<String, List<?>> emptyResourceLoader() {
        var m = new LinkedHashMap<String, List<?>>();
        m.put("extensions", List.of());
        m.put("skills", List.of());
        m.put("prompts", List.of());
        m.put("themes", List.of());
        m.put("agentsFiles", List.of());
        m.put("appendSystemPrompt", List.of());
        return m;
    }
}
