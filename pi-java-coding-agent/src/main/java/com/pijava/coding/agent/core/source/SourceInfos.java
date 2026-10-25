package com.pijava.coding.agent.core.source;

import com.pijava.agent.skill.Skill;

/**
 * 批 23（docs/34 T4）：{@link SourceInfo} 的共享折算件——RPC {@code get_commands}
 * 与扩展 {@code ctx.getCommands()} 两处消费同一规则（pi skills.ts:136-157
 * {@code createSkillSourceInfo}）。
 */
public final class SourceInfos {

    private SourceInfos() {
    }

    /**
     * 技能的 sourceInfo 三面映射：USER/PROJECT→同值 scope，EXPLICIT→temporary
     * （pi {@code "path"} 源 scope 缺省落 temporary）；{@code source} 恒
     * {@code "local"}、{@code origin} 恒 {@code "top-level"}。
     */
    public static SourceInfo fromSkill(Skill skill) {
        var scope = switch (skill.sourceInfo()) {
            case USER -> SourceInfo.Scope.USER;
            case PROJECT -> SourceInfo.Scope.PROJECT;
            case EXPLICIT -> SourceInfo.Scope.TEMPORARY;
        };
        var filePath = skill.filePath();
        var baseDir = skill.baseDir();
        return new SourceInfo(
            // pi path 必填；非文件技能（java 独有边）给空串。
            filePath == null ? "" : filePath.toString(),
            "local", scope, SourceInfo.Origin.TOP_LEVEL,
            baseDir == null ? null : baseDir.toString());
    }
}
