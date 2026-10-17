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

    /**
     * pi {@code addSkills}（skills.ts:425-456）对位：**先到先得** ——
     * 同文件（realPath，符号链接归一）静默跳过（:432-435）；同名后到者丢弃并产
     * {@code collision} 诊断（:437-449，含 winnerPath/loserPath）。
     */
    public static LoadSkillsResult dedupe(LoadSkillsResult result) {
        var byName = new java.util.LinkedHashMap<String, Skill>();
        var seenRealPaths = new java.util.HashSet<String>();
        var diagnostics = new java.util.ArrayList<>(result.diagnostics());
        for (Skill skill : result.skills()) {
            java.nio.file.Path file = fileOf(skill);
            if (file != null && !seenRealPaths.add(canonicalize(file))) {
                continue; // 符号链接同文件：静默跳过（pi :432-435）
            }
            Skill existing = byName.get(skill.name());
            if (existing != null) {
                diagnostics.add(ResourceDiagnostic.collision(
                    String.valueOf(file), "name \"" + skill.name() + "\" collision",
                    new ResourceDiagnostic.Collision("skill", skill.name(),
                        String.valueOf(fileOf(existing)), String.valueOf(file))));
                continue; // 先到先得：后到者丢弃（pi :437-449）
            }
            byName.put(skill.name(), skill);
        }
        return new LoadSkillsResult(List.copyOf(byName.values()), List.copyOf(diagnostics));
    }

    /** 技能文件路径（{@link Skill#filePath()}；非文件型技能 ⇒ {@code null}）。 */
    private static java.nio.file.Path fileOf(Skill skill) {
        return skill.filePath();
    }

    /** pi {@code canonicalizePath}：{@code toRealPath} 失败回落绝对路径。 */
    private static String canonicalize(java.nio.file.Path file) {
        try {
            return file.toRealPath().toString();
        } catch (java.io.IOException e) {
            return file.toAbsolutePath().normalize().toString();
        }
    }
}
