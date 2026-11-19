package com.pijava.examples.sdk;

/** pi {@code examples/sdk/03-custom-prompt.ts} 等价改写（docs/56 §2.2）——systemPrompt 替换/追加。 */
public final class Sdk03CustomPrompt {

    /** pi L21-22 完整替换的 pirate 提示逐字。 */
    public static final String REPLACE_PROMPT = String.join("\n",
        "You are a helpful assistant that speaks like a pirate.",
        "Always end responses with \"Arrr!\"");

    /** pi L53 追加段逐字。 */
    public static final String APPEND = "## Additional Instructions\n"
        + "- Always be concise\n- Use bullet points when listing things";

    /** pi L138 `(base) => [...base, APPEND]`。 */
    public static String appendSystemPrompt(String base) {
        return base + "\n" + APPEND;
    }

    public static final String PROMPT_1 = "What is 2 + 2?";
    public static final String PROMPT_2 = "List 3 benefits of TypeScript.";
    public static final String HEADER_REPLACE = "=== Replace prompt ===";
    public static final String HEADER_MODIFY = "=== Modify prompt ===";
}
