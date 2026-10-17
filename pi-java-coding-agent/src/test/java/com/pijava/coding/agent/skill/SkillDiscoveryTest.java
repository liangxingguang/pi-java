package com.pijava.coding.agent.skill;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.pijava.agent.skill.SkillSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6-6: SkillDiscovery — 目录三规则、忽略过滤、来源覆盖顺序、baseDir。
 */
class SkillDiscoveryTest {

    @TempDir
    Path tmp;

    @Test
    void directoryWithSkillMdIsSkillRootNoRecursion() throws Exception {
        var skillDir = Files.createDirectories(tmp.resolve("review"));
        Files.writeString(skillDir.resolve("SKILL.md"),
            "---\nname: review\ndescription: Review.\n---\nbody");
        Files.writeString(skillDir.resolve("helper.md"),
            "---\ndescription: should-not-load\n---\n");

        var result = new SkillDiscovery(tmp, tmp).loadDirectory(skillDir, SkillSource.USER, true);
        assertThat(result.skills()).hasSize(1);
        assertThat(result.skills().get(0).name()).isEqualTo("review");
    }

    @Test
    void rootMdFilesLoadedOnlyWithIncludeRootFiles() throws Exception {
        Files.writeString(tmp.resolve("root.md"),
            "---\nname: root\ndescription: Root.\n---\nbody");

        var withRoot = new SkillDiscovery(tmp, tmp).loadDirectory(tmp, SkillSource.USER, true);
        assertThat(withRoot.skills()).extracting(s -> s.name()).contains("root");

        var withoutRoot = new SkillDiscovery(tmp, tmp).loadDirectory(tmp, SkillSource.USER, false);
        assertThat(withoutRoot.skills()).isEmpty();
    }

    @Test
    void deepSkillMdFoundViaRecursion() throws Exception {
        var deep = Files.createDirectories(tmp.resolve("a/b/c"));
        Files.writeString(deep.resolve("SKILL.md"),
            "---\nname: deep-skill\ndescription: Deep.\n---\nbody");

        var result = new SkillDiscovery(tmp, tmp).loadDirectory(tmp, SkillSource.USER, false);
        assertThat(result.skills()).extracting(s -> s.name()).contains("deep-skill");
    }

    @Test
    void gitignoredSkillIsSkipped() throws Exception {
        Files.writeString(tmp.resolve(".gitignore"), "ignored/\n");
        var ignored = Files.createDirectories(tmp.resolve("ignored"));
        Files.writeString(ignored.resolve("SKILL.md"),
            "---\nname: should-skip\ndescription: nope\n---\nbody");
        var kept = Files.createDirectories(tmp.resolve("kept"));
        Files.writeString(kept.resolve("SKILL.md"),
            "---\nname: kept\ndescription: yes\n---\nbody");

        var result = new SkillDiscovery(tmp, tmp).loadDirectory(tmp, SkillSource.USER, false);
        assertThat(result.skills()).extracting(s -> s.name()).containsExactly("kept");
    }

    @Test
    void firstSourceWinsAndLaterCollisionIsDiagnosed() throws Exception {
        // pi addSkills（skills.ts:437-449）：先到先得，后到者产 collision 诊断。
        var userSkills = Files.createDirectories(tmp.resolve("user-skills"));
        var projSkills = Files.createDirectories(tmp.resolve("proj-skills"));
        Files.writeString(userSkills.resolve("SKILL.md"),
            "---\nname: shared\ndescription: user version\n---\nuser body");
        Files.writeString(projSkills.resolve("SKILL.md"),
            "---\nname: shared\ndescription: project version\n---\nproj body");

        var discovery = new SkillDiscovery(tmp, tmp);
        var user = discovery.loadDirectory(userSkills, SkillSource.USER, true);
        var proj = discovery.loadDirectory(projSkills, SkillSource.PROJECT, true);
        var merged = LoadSkillsResult.dedupe(
            LoadSkillsResult.merge(List.of(user, proj)));

        assertThat(merged.skills()).hasSize(1);
        assertThat(merged.skills().get(0).systemPrompt())
            .as("先到先得：USER 赢（pi 序 USER → PROJECT → 显式）")
            .contains("user body");
        var collisions = merged.diagnostics().stream()
            .filter(d -> "collision".equals(d.level())).toList();
        assertThat(collisions).hasSize(1);
        var payload = collisions.get(0).collision();
        assertThat(payload.name()).isEqualTo("shared");
        assertThat(payload.winnerPath()).contains("user-skills");
        assertThat(payload.loserPath()).contains("proj-skills");
    }

    @Test
    void sameFileViaSymlinkIsSilentlySkipped() throws Exception {
        // pi :432-435：realPath 同文件静默跳过（无诊断）。
        var real = Files.createDirectories(tmp.resolve("real"));
        Files.writeString(real.resolve("SKILL.md"),
            "---\nname: linked\ndescription: x\n---\nbody");
        var link = tmp.resolve("link");
        try {
            Files.createSymbolicLink(link, real);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symlink unavailable: " + e);
            return;
        }
        var discovery = new SkillDiscovery(tmp, tmp);
        var a = discovery.loadDirectory(real, SkillSource.EXPLICIT, true);
        var b = discovery.loadDirectory(link, SkillSource.EXPLICIT, true);
        var merged = LoadSkillsResult.dedupe(LoadSkillsResult.merge(List.of(a, b)));

        assertThat(merged.skills()).hasSize(1);
        assertThat(merged.diagnostics()).as("同文件静默跳过，无 collision").isEmpty();
    }

    @Test
    void explicitMissingOrNonMdPathsAreWarnings() throws Exception {
        // pi :484-485/:502：显式路径不存在 / 非 md ⇒ warning（不是 error）。
        Files.writeString(tmp.resolve("note.txt"), "not markdown");
        var discovery = new SkillDiscovery(tmp, tmp);
        var result = discovery.discoverAll(false,
            List.of(tmp.resolve("missing"), tmp.resolve("note.txt")));

        assertThat(result.skills()).isEmpty();
        assertThat(result.diagnostics()).extracting(ResourceDiagnostic::level)
            .containsExactly("warning", "warning");
        assertThat(result.diagnostics()).extracting(ResourceDiagnostic::message)
            .containsExactly("skill path does not exist", "skill path is not a markdown file");
    }

    @Test
    void explicitPathUnderDefaultDirsInfersSource() throws Exception {
        // pi getSource（:473-479）：includeDefaults=false 且落在默认目录下 ⇒ user/project。
        var agentDir = Files.createDirectories(tmp.resolve("agent"));
        var userSkill = Files.createDirectories(agentDir.resolve("skills/mine"));
        Files.writeString(userSkill.resolve("SKILL.md"),
            "---\nname: mine\ndescription: x\n---\nbody");
        var projSkill = Files.createDirectories(tmp.resolve(".pi-java/skills/ours"));
        Files.writeString(projSkill.resolve("SKILL.md"),
            "---\nname: ours\ndescription: x\n---\nbody");
        var outside = Files.createDirectories(tmp.resolve("elsewhere"));
        Files.writeString(outside.resolve("SKILL.md"),
            "---\nname: out\ndescription: x\n---\nbody");

        var discovery = new SkillDiscovery(tmp, agentDir);
        var result = discovery.discoverAll(false, List.of(userSkill, projSkill, outside));

        var byName = new java.util.HashMap<String, SkillSource>();
        for (var s : result.skills()) {
            byName.put(s.name(), s.sourceInfo());
        }
        assertThat(byName.get("mine")).isEqualTo(SkillSource.USER);
        assertThat(byName.get("ours")).isEqualTo(SkillSource.PROJECT);
        assertThat(byName.get("out")).isEqualTo(SkillSource.EXPLICIT);
    }

    @Test
    void baseDirIsSkillMdDirectory() throws Exception {
        var skillDir = Files.createDirectories(tmp.resolve("my-skill"));
        Files.writeString(skillDir.resolve("SKILL.md"),
            "---\ndescription: x\n---\nbody");
        var result = new SkillDiscovery(tmp, tmp).loadDirectory(skillDir, SkillSource.USER, true);
        assertThat(result.skills().get(0).baseDir()).isEqualTo(skillDir);
    }

    @Test
    void discoverAllAggregatesUserProjectAndExplicit() throws Exception {
        var agentDir = Files.createDirectories(tmp.resolve("agent"));
        var userSkills = Files.createDirectories(agentDir.resolve("skills"));
        Files.writeString(userSkills.resolve("SKILL.md"),
            "---\nname: user-skill\ndescription: u\n---\nuser");
        var projSkills = Files.createDirectories(tmp.resolve(".pi-java").resolve("skills"));
        Files.writeString(projSkills.resolve("SKILL.md"),
            "---\nname: proj-skill\ndescription: p\n---\nproj");
        var explicit = Files.createDirectories(tmp.resolve("explicit"));
        Files.writeString(explicit.resolve("SKILL.md"),
            "---\nname: explicit-skill\ndescription: e\n---\nexp");

        var discovery = new SkillDiscovery(tmp, agentDir);
        var result = discovery.discoverAll(true, List.of(explicit));
        assertThat(result.skills()).extracting(s -> s.name())
            .contains("user-skill", "proj-skill", "explicit-skill");
    }
}
