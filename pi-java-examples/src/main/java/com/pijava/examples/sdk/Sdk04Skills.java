package com.pijava.examples.sdk;

import java.util.ArrayList;
import java.util.List;

import com.pijava.agent.skill.Skill;

/**
 * pi {@code examples/sdk/04-skills.ts} 等价改写（docs/56 §2.1）——
 * DefaultResourceLoader.skillsOverride 发现后过滤+合并内联虚拟 skill，getSkills 复核。
 * Java 无同名 loader，投影为纯函数（形状差 D247）。
 */
public final class Sdk04Skills {

    /** 内联虚拟 skill 的描述投影（pi customSkill 逐字）。 */
    public record SkillProjection(String name, String description, String filePath,
                                  String baseDir, boolean disableModelInvocation) {}

    public static SkillProjection customSkill() {
        return new SkillProjection("my-skill", "Custom project instructions",
            "/virtual/SKILL.md", "/virtual", false);
    }

    /** pi L197：过滤 + 追加 customSkill。 */
    public static List<String> filteredSkillNames(List<String> current) {
        var result = new ArrayList<String>();
        for (var name : current) {
            if (name.contains("browser") || name.contains("search")) {
                result.add(name);
            }
        }
        result.add(customSkill().name());
        return result;
    }

    /** getSkills 输出文本逐字。 */
    public static List<String> describe(List<String> discovered, boolean hasDiagnostics) {
        var lines = new ArrayList<String>();
        lines.add("Discovered skills:");
        lines.addAll(discovered);
        if (hasDiagnostics) {
            lines.add("Warnings:");
        }
        lines.add("Session created with filtered skills");
        return lines;
    }
}
