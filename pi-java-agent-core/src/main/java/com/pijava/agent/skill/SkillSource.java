package com.pijava.agent.skill;

/**
 * 技能来源（对齐 pi {@code SkillSourceInfo}）。
 *
 * <p>用于 {@code get_commands} 上报与诊断。放在 agent-core 而非 coding-agent，
 * 因为 {@link Skill#sourceInfo()} 的返回类型不能在 agent-core 引用 coding-agent
 * 的类型（依赖方向 agent-core ← coding-agent）。</p>
 */
public enum SkillSource {
    /** 全局用户目录（~/.pi-java/agent/skills/）。 */
    USER,
    /** 项目级目录（&lt;project&gt;/.pi-java/skills/）。 */
    PROJECT,
    /** CLI --skill 显式路径。 */
    EXPLICIT
}
