package com.pijava.agent.skill;

import com.pijava.ai.api.ToolDefinition;
import java.nio.file.Path;
import java.util.List;

/**
 * A named skill that can be loaded into the agent's context.
 * Aligned with pi's Skill interface.
 */
public interface Skill {
    /** Unique skill name (e.g. "code-review", "tdd"). */
    String name();

    /** Human-readable label (pi-java 独有；Markdown 无 label 时回落为 name). */
    String label();

    /** Description shown to the LLM. */
    String description();

    /** Get the system prompt fragment for this skill. */
    String systemPrompt();

    /** Optional tool definitions contributed by this skill. */
    default List<ToolDefinition> tools() {
        return List.of();
    }

    /**
     * 技能 baseDir —— 正文内相对路径按此解析为绝对路径（Markdown 技能 =
     * {@code SKILL.md} 所在目录）。非 Markdown 技能返回 null。
     */
    default Path baseDir() {
        return null;
    }

    /**
     * 技能定义文件本身的路径 —— pi {@code Skill.filePath}（{@code skills.ts:77}）的对应物。
     *
     * <p>它只被系统提示的 {@code skills} 段用（渲染成 {@code <location>}，
     * {@code system-prompt.ts:376}）。pi-java 的技能实现是 Markdown 技能，
     * 其文件固定是 {@code baseDir/SKILL.md}（{@code MarkdownSkill} 的 baseDir 就是
     * {@code SKILL.md} 所在目录）；非文件型技能返回 {@code null}，该技能仍进
     * {@code <available_skills>}，只是 {@code <location>} 渲染成空串。</p>
     */
    default Path filePath() {
        var dir = baseDir();
        return dir == null ? null : dir.resolve("SKILL.md");
    }

    /** {@code true} 时不进系统提示（仅可显式调用）。 */
    default boolean disableModelInvocation() {
        return false;
    }

    /** 来源（user/project/显式路径），用于上报与诊断。 */
    default SkillSource sourceInfo() {
        return SkillSource.EXPLICIT;
    }
}
