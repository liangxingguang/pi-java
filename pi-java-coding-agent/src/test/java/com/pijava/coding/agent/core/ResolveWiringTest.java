package com.pijava.coding.agent.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.PackageSource;

/**
 * 批 18 T6（docs/29 §3/§7）：resolve() 统辖的会话装配接线（裁决⑨）——
 * settings.packages 本地包技能进会话／project 同名压 user（行为变化钉死）／
 * {@code !pattern} 关停包内技能／{@code --no-skills} 只裁 resolve 臂／
 * 模板同形接入。
 *
 * <p>user.dir/user.home 双重重定向（裁决⑨ 放大测试继承坑的对应面）；缺省
 * 真 home 的 {@code PI_JAVA_CODING_AGENT_DIR} 不设。</p>
 */
class ResolveWiringTest {

    @TempDir
    Path cwd;

    @TempDir
    Path home;

    private String oldUserDir;
    private String oldUserHome;
    private InMemorySettingsStorage storage;
    private SettingsManager settings;

    @BeforeEach
    void redirectDirs() {
        oldUserDir = System.getProperty("user.dir");
        oldUserHome = System.getProperty("user.home");
        System.setProperty("user.dir", cwd.toString());
        System.setProperty("user.home", home.toString());
        storage = new InMemorySettingsStorage();
        settings = SettingsManager.withStorage(storage);
    }

    @AfterEach
    void restoreDirs() {
        if (oldUserDir != null) {
            System.setProperty("user.dir", oldUserDir);
        }
        if (oldUserHome != null) {
            System.setProperty("user.home", oldUserHome);
        }
    }

    private Path agentDir() {
        return home.resolve(".pi-java").resolve("agent");
    }

    private static Path writeSkill(Path base, String name, String description)
            throws IOException {
        var file = base.resolve(name).resolve("SKILL.md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
            ---
            name: %s
            description: %s
            ---
            # %s
            """.formatted(name, description, name));
        return file;
    }

    private void writeGlobalPackages(PackageSource... sources) {
        var global = new Settings();
        global.packages = List.of(sources);
        storage.writeGlobal(global);
        settings.reload();
    }

    @Test
    void packageSkillEntersSessionViaResolve() throws Exception {
        var pkgDir = Files.createDirectories(cwd.resolve("pkg"));
        writeSkill(pkgDir.resolve("skills"), "pkg-skill", "from package");
        writeGlobalPackages(PackageSource.of(pkgDir.toString()));

        var skills = SessionSetup.discoverSkills(ArgsParser.parse(new String[0]), settings);

        assertThat(skills).containsKey("pkg-skill");
        assertThat(skills.get("pkg-skill").description()).isEqualTo("from package");
    }

    @Test
    void projectSkillShadowsSameNamedUserSkill() throws Exception {
        // 行为变化钉死（裁决⑨）：pi 五档优先级 project(1) < user(3) ⇒ project 胜；
        // 批 18 前旧臂 USER→PROJECT 先到先得是 user 胜。
        writeSkill(agentDir().resolve("skills"), "dup", "user variant");
        writeSkill(cwd.resolve(".pi-java").resolve("skills"), "dup", "project variant");

        var skills = SessionSetup.discoverSkills(ArgsParser.parse(new String[0]), settings);

        assertThat(skills.get("dup").description()).isEqualTo("project variant");
    }

    @Test
    void bangPatternDisablesPackageSkill() throws Exception {
        var pkgDir = Files.createDirectories(cwd.resolve("pkgBang"));
        writeSkill(pkgDir.resolve("skills"), "keep", "kept");
        writeSkill(pkgDir.resolve("skills"), "drop", "dropped");
        writeGlobalPackages(PackageSource.object(
            pkgDir.toString(), null, null, List.of("!drop"), null, null));

        var skills = SessionSetup.discoverSkills(ArgsParser.parse(new String[0]), settings);

        assertThat(skills).containsKey("keep");
        assertThat(skills).doesNotContainKey("drop");
    }

    @Test
    void noSkillsTrimsResolveArmButKeepsCli() throws Exception {
        // pi resource-loader :594 合并臂：noSkills ⇒ CLI 臂（-e 技能＋--skill）仍收。
        var pkgDir = Files.createDirectories(cwd.resolve("pkgNs"));
        writeSkill(pkgDir.resolve("skills"), "pkg-only", "package");
        writeGlobalPackages(PackageSource.of(pkgDir.toString()));
        var explicitDir = Files.createDirectories(cwd.resolve("explicit"));
        writeSkill(explicitDir, "cli-skill", "explicit");

        var skills = SessionSetup.discoverSkills(ArgsParser.parse(
            new String[] {"--no-skills", "--skill", explicitDir.toString()}), settings);

        assertThat(skills).doesNotContainKey("pkg-only");
        assertThat(skills).containsKey("cli-skill");
    }

    @Test
    void packagePromptTemplateEntersSession() throws Exception {
        var pkgDir = Files.createDirectories(cwd.resolve("pkgPrompt"));
        var promptsDir = Files.createDirectories(pkgDir.resolve("prompts"));
        Files.writeString(promptsDir.resolve("p.md"),
            "---\ndescription: from package\n---\nPackage prompt body");
        writeGlobalPackages(PackageSource.of(pkgDir.toString()));

        var templates = SessionSetup.loadPromptTemplates(
            ArgsParser.parse(new String[0]), settings);

        assertThat(templates).anySatisfy(t -> {
            assertThat(t.name()).isEqualTo("p");
            assertThat(t.description()).isEqualTo("from package");
        });
    }

    @Test
    void noPromptTemplatesKeepsCliExplicit() throws Exception {
        // 同 no-skills 合并臂（pi :596-600）。
        var pkgDir = Files.createDirectories(cwd.resolve("pkgNp"));
        var pkgPrompts = Files.createDirectories(pkgDir.resolve("prompts"));
        Files.writeString(pkgPrompts.resolve("pkg-only.md"), "package body");
        writeGlobalPackages(PackageSource.of(pkgDir.toString()));
        var explicitDir = Files.createDirectories(cwd.resolve("explicitP"));
        Files.writeString(explicitDir.resolve("cli.md"), "cli body");

        var templates = SessionSetup.loadPromptTemplates(ArgsParser.parse(
            new String[] {"--no-prompt-templates", "--prompt-template",
                explicitDir.toString()}), settings);

        assertThat(templates).noneMatch(t -> t.name().equals("pkg-only"));
        assertThat(templates).anyMatch(t -> t.name().equals("cli"));
    }

    @Test
    void extensionFlagResolvesLocalDirViaTemporary() throws Exception {
        // -e 本地目录走 resolveExtensionSources(temporary)（目录本身成扩展路径，
        // 消费点 resolveExtensionEntries 找 index.jar——见 AgentSession 臂）。
        var extDir = Files.createDirectories(cwd.resolve("ext"));
        Files.writeString(extDir.resolve("index.jar"), "jar");

        var resolved = SessionSetup.resolveCliSources(
            ArgsParser.parse(new String[] {"-e", extDir.toString()}),
            SessionSetup.packageManagerFor(settings));

        assertThat(resolved.extensions())
            .anySatisfy(r -> {
                assertThat(r.path()).isEqualTo(extDir.toString());
                assertThat(r.metadata().scope())
                    .isEqualTo(com.pijava.coding.agent.pkg.SourceScope.TEMPORARY);
            });
    }
}
