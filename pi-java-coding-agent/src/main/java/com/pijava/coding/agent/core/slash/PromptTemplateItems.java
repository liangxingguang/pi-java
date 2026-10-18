package com.pijava.coding.agent.core.slash;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.pijava.coding.agent.prompt.PromptTemplates.PromptTemplate;

/**
 * B247：prompt template 补全条目（pi interactive-mode.ts:787-792）。
 *
 * <p><b>无条件</b>注册（对照 skill 臂的 {@code enableSkillCommands} 门——pi
 * 模板臂无门）；条目带 {@code argumentHint}（pi :790）；模板名撞内置不去重
 * （pi 同形——提交时内置先匹配胜出）。与 {@link SkillCommandItems} 同形：
 * 条目只活在这份补全名单里，提交时随普通 prompt 走展开器
 * （{@link CommandRegistry#dispatch}）。</p>
 */
public final class PromptTemplateItems {

    /** 一条补全目录条目（name 不带 {@code /} 前缀）。 */
    public record Entry(String name, String description, String argumentHint) {}

    private PromptTemplateItems() {}

    /** 全部已注册模板的补全条目（pi :787-792，无门）。 */
    public static List<Entry> list(Collection<? extends PromptTemplate> templates) {
        var entries = new ArrayList<Entry>(templates.size());
        for (var template : templates) {
            entries.add(new Entry(template.name(),
                taggedDescription(template), template.argumentHint()));
        }
        return entries;
    }

    /**
     * pi {@code prefixAutocompleteDescription}（:702-708）经模板 sourceInfo：
     * {@code [tag] description}；描述空 ⇒ 仅 {@code [tag]}；无 sourceInfo
     * （兼容构造/手造实例）⇒ 描述原文（pi :703-705 sourceInfo 缺席短路）。
     */
    static String taggedDescription(PromptTemplate template) {
        var info = template.sourceInfo();
        if (info == null) {
            return template.description();
        }
        var tag = SkillCommandItems.sourceTag(info.scope());
        var description = template.description();
        return description == null || description.isEmpty()
            ? "[" + tag + "]"
            : "[" + tag + "] " + description;
    }
}
