package com.pijava.coding.agent.skill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.logging.Logger;

import com.pijava.agent.skill.Skill;

/**
 * pi {@code _expandSkillCommand}（{@code agent-session.ts:2147-2171}）对位（map #62）。
 *
 * <p>（{@code formatSkillInvocation} 已随 pi {@code 7fd478a2e} 删除 —— R1 不复活，
 * 现行行为就是这个内联形状，docs/09 §13.4 裁决④。）</p>
 *
 * <p>展开序：先 {@code /skill:} 后模板（两调用点 :2008-2009 / :2191-2192 同序）。</p>
 */
public final class SkillCommandExpansion {

    private static final Logger LOG = Logger.getLogger(SkillCommandExpansion.class.getName());

    private SkillCommandExpansion() {}

    /**
     * 展开 {@code /skill:<name> [args]}；非技能命令 / 未知名 / 读失败 ⇒ 原文透传。
     *
     * @param text   原始 prompt
     * @param skills 名字 → 技能（先到先得后的赢家表）
     */
    public static String expand(String text, Map<String, Skill> skills) {
        if (!text.startsWith("/skill:")) {
            return text;
        }
        int spaceIndex = text.indexOf(' ');
        String skillName = spaceIndex == -1 ? text.substring(7) : text.substring(7, spaceIndex);
        String args = spaceIndex == -1 ? "" : text.substring(spaceIndex + 1).trim();
        Skill skill = skills.get(skillName);
        if (skill == null) {
            return text; // Unknown skill, pass through
        }
        Path file = skill.filePath();
        if (file == null) {
            return text;
        }
        try {
            // pi :2159-2160：展开时惰性重读 + 剥前言（不信用加载期的正文快照）。
            String content = Files.readString(file, StandardCharsets.UTF_8);
            String body = FrontmatterParser.parse(content).body().trim();
            String block = "<skill name=\"" + skill.name() + "\" location=\""
                + file + "\">\nReferences are relative to "
                + skillBaseDir(skill) + ".\n\n" + body + "\n</skill>";
            return args.isEmpty() ? block : block + "\n\n" + args;
        } catch (IOException | FrontmatterParser.FrontmatterException e) {
            // pi :2164-2170 emitError({event:"skill_expansion"}) —— 扩展错误总线缺位，
            // 先 LOG，原文透传（事件随扩展包，docs/09 §13.5 留痕）。
            LOG.warning("[skill] expansion failed for " + skill.name() + ": " + e.getMessage());
            return text;
        }
    }

    private static String skillBaseDir(Skill skill) {
        if (skill instanceof MarkdownSkill md) {
            return String.valueOf(md.baseDir());
        }
        return "";
    }
}
