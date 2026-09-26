package com.pijava.agent.prompt;

import java.util.ArrayList;
import java.util.List;

import com.pijava.agent.skill.Skill;

/**
 * pi {@code formatSkillsForPrompt}（{@code packages/coding-agent/src/core/skills.ts:355-392}）
 * 的移植：技能清单段的正文。
 *
 * <p>段正文自带前导 {@code "\n\n"}（pi 逐字如此），调用方 {@code trim()} 之后再决定要不要产出段
 * —— {@code system-prompt.ts:171-174}。整段文本（三行指引 ＋ {@code <available_skills>} XML）
 * 是**结构**，逐字照抄；只有 {@code <location>} 的取值随技能实现。</p>
 */
public final class SkillsPrompt {

    private SkillsPrompt() {}

    /** pi 的 {@code fileReadTool} 参数取值（{@code :355}）。 */
    public static final String READ_TOOL = "read";

    /** pi 的 {@code fileReadTool === "bash"} 那一支（{@code :366}）。 */
    public static final String BASH_TOOL = "bash";

    /**
     * pi {@code :355-383} —— 不可见技能先滤掉；滤空返回空串（调用方据此不产出段）。
     *
     * @param fileReadTool {@link #READ_TOOL} 或 {@link #BASH_TOOL}，决定第二行指引
     */
    public static String format(List<Skill> skills, String fileReadTool) {
        var visible = new ArrayList<Skill>();
        for (var skill : skills) {
            if (!skill.disableModelInvocation()) {
                visible.add(skill);
            }
        }
        if (visible.isEmpty()) {
            return "";
        }
        var lines = new ArrayList<String>();
        lines.add("");
        lines.add("");
        lines.add("The following skills provide specialized instructions for specific tasks.");
        lines.add(READ_TOOL.equals(fileReadTool)
            ? "Use the read tool to load a skill's file when the task matches its description."
            : "Use bash to load a skill's file when the task matches its description.");
        lines.add("When a skill file references a relative path, resolve it against the skill "
            + "directory (parent of SKILL.md / dirname of the path) and use that absolute path "
            + "in tool commands.");
        lines.add("");
        lines.add("<available_skills>");
        for (var skill : visible) {
            lines.add("  <skill>");
            lines.add("    <name>" + escapeXml(skill.name()) + "</name>");
            lines.add("    <description>" + escapeXml(skill.description()) + "</description>");
            lines.add("    <location>" + escapeXml(location(skill)) + "</location>");
            lines.add("  </skill>");
        }
        lines.add("</available_skills>");
        return String.join("\n", lines);
    }

    /** {@code <location>} ＝ pi 的 {@code skill.filePath}（{@code type: Path}，null 渲染成空串）。 */
    private static String location(Skill skill) {
        var path = skill.filePath();
        return path == null ? "" : path.toString();
    }

    /**
     * pi {@code escapeXml}（{@code :385-392}）—— 五个 XML 文本位。
     *
     * <p>⚠️ {@code &} 必须**第一个**替换：先换 {@code <} 会让后换的 {@code &} 把已生成的
     * {@code &lt;} 再转义一次。</p>
     */
    static String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;");
    }
}
