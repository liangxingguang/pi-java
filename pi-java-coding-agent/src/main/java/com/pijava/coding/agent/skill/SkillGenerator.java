package com.pijava.coding.agent.skill;

import java.util.regex.Pattern;

/**
 * AI 生成 Skills（P6-27）：把技能元数据编排成给模型的 SKILL.md 生成提示，
 * 并从模型输出中提取干净的 markdown 正文。
 */
public final class SkillGenerator {

    private static final Pattern NAME = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?");

    private SkillGenerator() {}

    /** 校验技能名（对齐 pi {@code validateName}：小写字母/数字/连字符，≤64）。 */
    public static boolean isValidName(String name) {
        return name != null && name.length() <= 64 && NAME.matcher(name).matches();
    }

    /** 构造生成提示：要求模型只输出完整 SKILL.md（frontmatter + 正文）。 */
    public static String prompt(String name, String description) {
        return """
            Create an Agent Skill in SKILL.md format.

            Metadata:
            - name: %s
            - description: %s

            Return ONLY the complete markdown file, starting with the YAML
            frontmatter:

            ---
            name: %s
            description: %s
            ---

            followed by the skill body: 2-5 concise, actionable paragraphs that
            teach an AI agent how to do this task. Do not wrap the output in code
            fences and do not add commentary.
            """.formatted(name, description, name, description);
    }

    /** 从模型输出提取 markdown：去掉首尾代码围栏与包裹注释。 */
    public static String extractMarkdown(String modelText) {
        var trimmed = modelText.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                return trimmed.substring(firstNewline + 1, lastFence).trim() + "\n";
            }
        }
        return trimmed + "\n";
    }
}
