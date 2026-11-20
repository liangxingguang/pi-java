package com.pijava.coding.agent.core;

import java.util.List;

import com.pijava.coding.agent.skill.ResourceDiagnostic;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/65（L7 W5，pi {@code interactive-mode.ts:1820-1871}）：技能诊断行形——
 * collision 三行、普通诊断两行（带路径）/一行（无路径）。
 */
class SkillDiagnosticsTest {

    @Test
    void collisionRendersWinnerAndSkippedLoser() {
        var diagnostic = ResourceDiagnostic.collision("/skills/b.md", "dup",
            new ResourceDiagnostic.Collision("skill", "deploy", "/skills/a.md", "/skills/b.md"));

        assertThat(SkillDiagnostics.format(List.of(diagnostic))).containsExactly(
            "  \"deploy\" collision:",
            "    \u2713 /skills/a.md",
            "    \u2717 /skills/b.md (skipped)");
    }

    @Test
    void plainDiagnosticsIndentTheMessageUnderThePath() {
        assertThat(SkillDiagnostics.format(List.of(
            ResourceDiagnostic.warning("/skills/x.md", "missing description"))))
            .containsExactly("  /skills/x.md", "    missing description");

        assertThat(SkillDiagnostics.format(List.of(
            ResourceDiagnostic.error(null, "skills directory unreadable"))))
            .containsExactly("    skills directory unreadable");
    }

    @Test
    void blockCarriesTheSectionTitleAndDisappearsWhenEmpty() {
        assertThat(SkillDiagnostics.block(List.of())).isNull();
        assertThat(SkillDiagnostics.block(List.of(
            ResourceDiagnostic.warning("/s/x.md", "oops"))))
            .isEqualTo("[Skill warnings]\n  /s/x.md\n    oops");
    }
}
