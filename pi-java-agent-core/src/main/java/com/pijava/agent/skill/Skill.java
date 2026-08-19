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

    /** {@code true} 时不进系统提示（仅可显式调用）。 */
    default boolean disableModelInvocation() {
        return false;
    }

    /** 来源（user/project/显式路径），用于上报与诊断。 */
    default SkillSource sourceInfo() {
        return SkillSource.EXPLICIT;
    }
}
