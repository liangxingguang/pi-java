package com.pijava.coding.agent.core.slash;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.pijava.agent.skill.Skill;
import com.pijava.agent.skill.SkillSource;

/**
 * B244：{@code skill:<name>} 补全条目（pi interactive-mode.ts:803-815）。
 * 条目只有 name＋description（pi 无 argumentHint/handler——它不是真命令，
 * 提交时随普通 prompt 走展开器，见 {@link CommandRegistry#dispatch}）。
 */
public final class SkillCommandItems {

    /** 一条补全目录条目（name 不带 {@code /} 前缀）。 */
    public record Entry(String name, String description) {}

    private SkillCommandItems() {}

    /**
     * 受 {@code enableSkillCommands} 门的 skill 条目清单（pi :807-814）；
     * {@code disableModelInvocation} 技能照样列出（pi 不过滤——该标志只管系统提示）。
     */
    public static List<Entry> list(Collection<? extends Skill> skills, boolean enabled) {
        if (!enabled) {
            return List.of();
        }
        var entries = new ArrayList<Entry>(skills.size());
        for (var skill : skills) {
            entries.add(new Entry("skill:" + skill.name(), taggedDescription(skill)));
        }
        return entries;
    }

    /**
     * pi {@code prefixAutocompleteDescription}（:702-708）：
     * {@code [tag] description}；描述空 ⇒ 仅 {@code [tag]}。
     */
    static String taggedDescription(Skill skill) {
        var tag = sourceTag(skill.sourceInfo());
        var description = skill.description();
        return description == null || description.isEmpty()
            ? "[" + tag + "]"
            : "[" + tag + "] " + description;
    }

    /**
     * pi {@code getAutocompleteSourceTag}（:676-700）三值子集：user→u、
     * project→p、显式路径→t（pi 显式路径 scope 缺省，skills.ts:150-154，
     * 落 else 分支）。npm:/git: 复合标签无对位（java 无包源加载，窄差 D2）。
     */
    static String sourceTag(SkillSource source) {
        return switch (source) {
            case USER -> "u";
            case PROJECT -> "p";
            case EXPLICIT -> "t";
        };
    }

    /**
     * B247：scope 直取版（模板臂用，{@link PromptTemplateItems}）——pi
     * {@code getAutocompleteSourceTag} :683 的 scope 三值分支。
     */
    static String sourceTag(com.pijava.coding.agent.core.source.SourceInfo.Scope scope) {
        return switch (scope) {
            case USER -> "u";
            case PROJECT -> "p";
            case TEMPORARY -> "t";
        };
    }
}
