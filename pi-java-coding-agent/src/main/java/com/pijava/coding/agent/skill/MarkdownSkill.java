package com.pijava.coding.agent.skill;

import java.nio.file.Path;

import com.pijava.agent.skill.Skill;
import com.pijava.agent.skill.SkillSource;

/**
 * Markdown 技能文件加载后的 {@link Skill} 实现。
 *
 * <p>{@code baseDir} 为 {@code SKILL.md} 所在目录（正文内相对路径按此解析）；
 * {@code disableModelInvocation} 对应前言 {@code disable-model-invocation: true}；
 * {@code sourceInfo} 为来源（user/project/显式路径）。</p>
 */
public record MarkdownSkill(
    String name,
    String label,
    String description,
    String systemPrompt,
    Path baseDir,
    boolean disableModelInvocation,
    SkillSource sourceInfo
) implements Skill {

    /** 构造技能；label 缺失时回落为 name（设计 §6.4）。 */
    public MarkdownSkill {
        if (label == null || label.isBlank()) {
            label = name;
        }
    }
}
