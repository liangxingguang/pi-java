package com.pijava.coding.agent.skill;

import java.util.List;
import java.util.Optional;

import com.pijava.agent.skill.Skill;

/**
 * 单个技能文件加载结果 —— 技能 + 诊断一起返回，非法不中断整批加载。
 */
public record LoadSkillResult(
    Optional<Skill> skill,
    List<ResourceDiagnostic> diagnostics
) {
    /** 合法技能。 */
    public static LoadSkillResult of(Skill skill) {
        return new LoadSkillResult(Optional.of(skill), List.of());
    }

    /** 被跳过的文件（携带诊断）。 */
    public static LoadSkillResult skipped(List<ResourceDiagnostic> diagnostics) {
        return new LoadSkillResult(Optional.empty(), List.copyOf(diagnostics));
    }
}
