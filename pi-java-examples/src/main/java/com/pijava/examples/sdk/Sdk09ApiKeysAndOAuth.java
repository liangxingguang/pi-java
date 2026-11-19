package com.pijava.examples.sdk;

import java.util.List;

/**
 * pi {@code examples/sdk/09-api-keys-and-oauth.ts} 等价改写（docs/56 §2.1）——
 * ModelRuntime 三入口（默认/自定义路径/运行时 key 覆盖）投影（形状差 D247/D233）。
 */
public final class Sdk09ApiKeysAndOAuth {

    public record RuntimeConfig(String authPath, String modelsPath, String runtimeKeyProvider,
                                String runtimeKeyValue, String message) {}

    /** pi L9-34 三次 createAgentSession 的认证配置投影。 */
    public static List<RuntimeConfig> runtimes() {
        return List.of(
            new RuntimeConfig(null, null, null, null, "Session with default model runtime"),
            new RuntimeConfig("/tmp/my-app/auth.json", "/tmp/my-app/models.json", null, null,
                "Session with custom auth and models locations"),
            new RuntimeConfig(null, null, "anthropic", "sk-my-temp-key",
                "Session with runtime API key override"));
    }
}
