package com.pijava.coding.agent.core.slash;

import java.util.List;

import com.pijava.agent.skill.Skill;
import com.pijava.agent.skill.SkillSource;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * B244（docs/12 §3.3）：{@code skill:<name>} 补全条目 —— 开关门、u/p/t
 * 来源标签（pi {@code getAutocompleteSourceTag} 三值子集）、空描述仅
 * {@code [tag]}、{@code disableModelInvocation} 技能照列。
 */
class SkillCommandItemsTest {

    @Test
    void enabledListsOneEntryPerSkill() {
        var entries = SkillCommandItems.list(List.of(
            skill("alpha", "Alpha skill", SkillSource.USER, false),
            skill("beta", "Beta skill", SkillSource.PROJECT, false)), true);

        assertThat(entries).containsExactly(
            new SkillCommandItems.Entry("skill:alpha", "[u] Alpha skill"),
            new SkillCommandItems.Entry("skill:beta", "[p] Beta skill"));
    }

    @Test
    void disabledListsNothing() {
        var entries = SkillCommandItems.list(List.of(
            skill("alpha", "Alpha skill", SkillSource.USER, false)), false);

        assertThat(entries).isEmpty();
    }

    @Test
    void sourceTagsFollowPiThreeValueSubset() {
        assertThat(SkillCommandItems.sourceTag(SkillSource.USER)).isEqualTo("u");
        assertThat(SkillCommandItems.sourceTag(SkillSource.PROJECT)).isEqualTo("p");
        // pi 显式路径 scope 缺省落 else 分支 ⇒ "t"（skills.ts:150-154）。
        assertThat(SkillCommandItems.sourceTag(SkillSource.EXPLICIT)).isEqualTo("t");
    }

    @Test
    void emptyDescriptionRendersTagOnly() {
        var entries = SkillCommandItems.list(List.of(
            skill("nodesc", "", SkillSource.EXPLICIT, false)), true);

        assertThat(entries)
            .containsExactly(new SkillCommandItems.Entry("skill:nodesc", "[t]"));
    }

    @Test
    void disableModelInvocationSkillsAreStillListed() {
        // pi 不过滤——该标志只管系统提示，不妨碍用户显式调用。
        var entries = SkillCommandItems.list(List.of(
            skill("hidden", "Hidden skill", SkillSource.PROJECT, true)), true);

        assertThat(entries)
            .containsExactly(
                new SkillCommandItems.Entry("skill:hidden", "[p] Hidden skill"));
    }

    private static Skill skill(String name, String description,
                               SkillSource source, boolean disableModelInvocation) {
        return new Skill() {
            @Override public String name() { return name; }
            @Override public String label() { return name; }
            @Override public String description() { return description; }
            @Override public String systemPrompt() { return ""; }
            @Override public SkillSource sourceInfo() { return source; }
            @Override public boolean disableModelInvocation() {
                return disableModelInvocation;
            }
        };
    }
}
