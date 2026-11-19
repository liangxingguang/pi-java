package com.pijava.examples.sdk;

import java.util.ArrayList;
import java.util.List;

/**
 * pi {@code examples/sdk/08-prompt-templates.ts} 等价改写（docs/56 §2.1）——
 * promptsOverride 注入文件式 deploy 模板（形状差 D247）。
 */
public final class Sdk08PromptTemplates {

    public record PromptTemplate(String name, String description, String filePath, String content) {}

    /** pi L17-27 deployTemplate 逐字。 */
    public static PromptTemplate deployTemplate() {
        return new PromptTemplate("deploy", "Deploy the application",
            "/virtual/prompts/deploy.md", String.join("\n",
                "# Deploy Instructions",
                "",
                "1. Build: npm run build",
                "2. Test: npm test",
                "3. Deploy: npm run deploy"));
    }

    /** pi L35：保留 current 并追加。 */
    public static List<PromptTemplate> promptsOverride(List<PromptTemplate> current) {
        var result = new ArrayList<>(current);
        result.add(deployTemplate());
        return result;
    }

    /** pi L41-50 输出逐字。 */
    public static List<String> describe(List<PromptTemplate> discovered) {
        var lines = new ArrayList<String>();
        lines.add("Discovered prompt templates:");
        for (var t : discovered) {
            lines.add("  /" + t.name() + ": " + t.description());
        }
        lines.add("Session created with " + (discovered.size() + 1) + " prompt templates");
        return lines;
    }
}
