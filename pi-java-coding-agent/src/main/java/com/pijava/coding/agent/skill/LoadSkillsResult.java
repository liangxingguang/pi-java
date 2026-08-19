package com.pijava.coding.agent.skill;

import java.util.List;

import com.pijava.agent.skill.Skill;

/**
 * 批量技能加载结果 —— 技能列表 + 诊断列表（对齐设计 §6.3）。
 */
public record LoadSkillsResult(
    List<Skill> skills,
    List<ResourceDiagnostic> diagnostics
) {
    /** 空结果。 */
    public static LoadSkillsResult empty() {
        return new LoadSkillsResult(List.of(), List.of());
    }

    /** 合并多个子结果。 */
    public static LoadSkillsResult merge(List<LoadSkillsResult> results) {
        var skills = new java.util.ArrayList<Skill>();
        var diagnostics = new java.util.ArrayList<ResourceDiagnostic>();
        for (var r : results) {
            skills.addAll(r.skills());
            diagnostics.addAll(r.diagnostics());
        }
        return new LoadSkillsResult(List.copyOf(skills), List.copyOf(diagnostics));
    }

    /** 按 name 去重，后加载覆盖先加载（保序）。 */
    public static LoadSkillsResult dedupe(LoadSkillsResult result) {
        var byName = new java.util.LinkedHashMap<String, Skill>();
        for (var skill : result.skills()) {
            byName.put(skill.name(), skill);
        }
        return new LoadSkillsResult(List.copyOf(byName.values()), result.diagnostics());
    }
}
