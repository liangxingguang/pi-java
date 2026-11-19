package com.pijava.examples.sdk;

/** pi {@code examples/sdk/01-minimal.ts} 等价改写（docs/56 §2.2）——零配置最小闭环。 */
public final class Sdk01Minimal {

    /** pi L19 默认提示词逐字。 */
    public static final String PROMPT = "What files are in the current directory?";

    /** 订阅事件过滤谓词（pi L14）。 */
    public static boolean isTextDelta(SdkRunSupport.RunOutcome ignored) {
        // 形状对齐：Java 侧在 SdkRunSupport.subscribe 内判定；此处保留谓词入口
        return true;
    }

    public static SdkRunSupport.RunOutcome run(
            java.util.function.Supplier<com.pijava.coding.agent.core.AgentSession> factory) {
        return SdkRunSupport.run(factory, PROMPT);
    }
}
