package com.pijava.examples.sdk;

import java.util.List;

/** pi {@code examples/sdk/02-custom-model.ts} 等价改写（docs/56 §2.2）——模型查找+thinkingLevel。 */
public final class Sdk02CustomModel {

    public static final String PROMPT = "Say hello in one sentence.";
    public static final String THINKING_LEVEL = "medium";

    /** pi L12/L18/L24 三种模型查找入口投影。 */
    public record ModelLookup(String provider, String id, String message) {}

    public static List<ModelLookup> lookups() {
        return List.of(
            new ModelLookup("anthropic", "claude-opus-4-5", "Found model: anthropic/claude-opus-4-5"),
            new ModelLookup("my-provider", "my-model", "Found custom model: my-provider/my-model"));
    }

    public static SdkRunSupport.RunOutcome run(
            java.util.function.Supplier<com.pijava.coding.agent.core.AgentSession> factory) {
        return SdkRunSupport.run(factory, PROMPT);
    }
}
