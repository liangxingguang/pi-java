package com.pijava.examples.sdk;

import java.util.List;

/** pi {@code examples/sdk/06-extensions.ts} 等价改写（docs/56 §2.2）——额外扩展路径+内联工厂。 */
public final class Sdk06Extensions {

    public static final String PROMPT = "List files in the current directory.";
    public static final String INLINE_EVENT_TEXT = "[Inline Extension] Agent starting";

    /** pi L287 additionalExtensionPaths 逐字。 */
    public static List<String> additionalExtensionPaths() {
        return List.of("./my-logging-extension.ts", "./my-safety-extension.ts");
    }

    /** 块注释示例（L58-99，D250 不执行）中的文本投影。 */
    public static List<String> commentedExampleTexts() {
        return List.of(
            "[Extension] Agent starting",
            "[Extension] Tool: bash",
            "Processed: input");
    }
}
