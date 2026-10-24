package com.pijava.coding.agent.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

import com.pijava.coding.agent.core.ConfigResourceWriteBack.ConfigItem;
import com.pijava.coding.agent.core.ConfigResourceWriteBack.ProjectOverrideState;
import com.pijava.coding.agent.pkg.PathMetadata;
import com.pijava.coding.agent.pkg.PathMetadata.Origin;
import com.pijava.coding.agent.pkg.ResourceType;
import com.pijava.coding.agent.pkg.SourceResolver;
import com.pijava.coding.agent.pkg.SourceScope;

/**
 * 批 19 T6（docs/30 §7）：config 写回四函数＋三态循环——pattern 取值（相对
 * 基目录/builtin 全路径/包根相对）、剥前缀去重、对象形升级与折叠回纯串、
 * project 覆写（无前缀声明条目/autoload:false 新建/整条删除）、继承态读取。
 */
class ConfigWriteBackTest {

    /** 相对路径串（Windows 下与 node path.relative 同形＝反斜杠分隔）。 */
    private static String rel(String first, String... more) {
        var parts = new String[more.length + 1];
        parts[0] = first;
        System.arraycopy(more, 0, parts, 1, more.length);
        return String.join(java.io.File.separator, parts);
    }

    @TempDir
    Path agentDir;

    @TempDir
    Path cwd;

    private SettingsManager manager;
    private ConfigResourceWriteBack writeBack;

    @BeforeEach
    void setUp() {
        manager = SettingsManager.withStorage(new InMemorySettingsStorage());
        var resolver = new SourceResolver(cwd, agentDir, manager::isProjectTrusted);
        writeBack = new ConfigResourceWriteBack(manager, resolver, cwd, agentDir);
    }

    /** user 域 top-level skill 项（自动发现形：baseDir=agentDir）。 */
    private ConfigItem userSkill(String name) {
        var path = agentDir.resolve("skills").resolve(name).resolve("SKILL.md");
        return new ConfigItem(path.toString(), true, ResourceType.SKILLS,
            new PathMetadata("auto", SourceScope.USER, Origin.TOP_LEVEL,
                agentDir.toString(), null));
    }

    private ConfigItem packageItem(String source, Path packageRoot, String relPath,
            ResourceType type) {
        return new ConfigItem(packageRoot.resolve(relPath).toString(), true, type,
            new PathMetadata(source, SourceScope.USER, Origin.PACKAGE,
                packageRoot.toString(), packageRoot.toString()));
    }

    // ── toggleTopLevelResource（pi :538-584）─────────────────────

    @Test
    void topLevelDisablePushesMinusRelativePattern() {
        writeBack.toggleTopLevelResource(userSkill("foo"), false);

        assertThat(manager.global().skills).containsExactly("-" + rel("skills", "foo", "SKILL.md"));
    }

    @Test
    void topLevelToggleStripsPrefixedDuplicates() {
        manager.global().skills =
            new java.util.ArrayList<>(List.of(rel("skills", "foo", "SKILL.md"), "+other", "-" + rel("skills", "foo", "SKILL.md")));

        writeBack.toggleTopLevelResource(userSkill("foo"), true);

        assertThat(manager.global().skills).containsExactly("+other", "+" + rel("skills", "foo", "SKILL.md"));
    }

    @Test
    void builtinPatternIsFullPath() {
        var item = new ConfigItem("builtin:mcp", true, ResourceType.EXTENSIONS,
            new PathMetadata("builtin", SourceScope.USER, Origin.TOP_LEVEL, null, null));

        writeBack.toggleTopLevelResource(item, false);

        assertThat(manager.global().extensions).containsExactly("-builtin:mcp");
    }

    // ── togglePackageResource（pi :586-643）──────────────────────

    @Test
    void packageToggleUpgradesStringFormToObject() throws IOException {
        manager.global().packages = new java.util.ArrayList<>(List.of(PackageSource.of("npm:foo")));
        var root = agentDir.resolve("npm/node_modules/foo");
        Files.createDirectories(root);

        writeBack.togglePackageResource(
            packageItem("npm:foo", root, "extensions/a.jar", ResourceType.EXTENSIONS), false);

        var pkg = manager.global().packages.get(0);
        assertThat(pkg.stringForm()).isFalse();
        assertThat(pkg.source()).isEqualTo("npm:foo");
        assertThat(pkg.extensions()).containsExactly("-" + rel("extensions", "a.jar"));
    }

    @Test
    void packageToggleCollapsesBackToBareStringWhenFiltersEmpty() throws IOException {
        manager.global().packages = new java.util.ArrayList<>(List.of(
            PackageSource.object("npm:foo", null, List.of("-" + rel("extensions", "a.jar")),
                null, null, null)));
        var root = agentDir.resolve("npm/node_modules/foo");
        Files.createDirectories(root);

        writeBack.togglePackageResource(
            packageItem("npm:foo", root, "extensions/a.jar", ResourceType.EXTENSIONS), true);

        // 先剥后推 +pattern：剩余一枚过滤键非空 ⇒ 保持对象形
        var pkg = manager.global().packages.get(0);
        assertThat(pkg.stringForm()).isFalse();
        assertThat(pkg.extensions()).containsExactly("+" + rel("extensions", "a.jar"));
    }

    @Test
    void packageTogglePreservesSiblingFilterArrays() throws IOException {
        // pi 注记：toggle 臂恒推一枚 pattern ⇒ filters 永不全空，:628-636 的
        // hasFilters=false 折叠在 global 臂不可达（project 臂可达，另有测试）。
        manager.global().packages = new java.util.ArrayList<>(List.of(
            PackageSource.object("npm:foo", null, List.of("-" + rel("extensions", "a.jar")),
                List.of("+skills/keep"), null, null)));
        var root = agentDir.resolve("npm/node_modules/foo");
        Files.createDirectories(root);

        writeBack.togglePackageResource(
            packageItem("npm:foo", root, "extensions/a.jar", ResourceType.EXTENSIONS), true);

        var pkg = manager.global().packages.get(0);
        assertThat(pkg.extensions()).containsExactly("+" + rel("extensions", "a.jar"));
        assertThat(pkg.skills()).containsExactly("+skills/keep");   // 兄弟键不动
    }

    // ── 三态循环（pi :739-745 真值表逐字）────────────────────────

    @Test
    void nextOverrideStateTruthTable() {
        // 直接调 getNextOverrideState 需要 project settings 条目配合；用纯函数面：
        // inherit + inheritedEnabled ⇒ unload；inherit + !inheritedEnabled ⇒ load
        // unload + inheritedEnabled ⇒ load；unload + !inheritedEnabled ⇒ inherit
        // load + inheritedEnabled ⇒ inherit；load + !inheritedEnabled ⇒ unload
        assertThat(writeBack.getNextOverrideState(topLevelItemForState(null), true))
            .isEqualTo(ProjectOverrideState.UNLOAD);
        assertThat(writeBack.getNextOverrideState(topLevelItemForState(null), false))
            .isEqualTo(ProjectOverrideState.LOAD);
        manager.project().skills =
            new java.util.ArrayList<>(List.of("-" + rel("skills", "foo", "SKILL.md")));
        assertThat(writeBack.getNextOverrideState(topLevelItemForState(null), true))
            .isEqualTo(ProjectOverrideState.LOAD);
        assertThat(writeBack.getNextOverrideState(topLevelItemForState(null), false))
            .isEqualTo(ProjectOverrideState.INHERIT);
        manager.project().skills =
            new java.util.ArrayList<>(List.of("+" + rel("skills", "foo", "SKILL.md")));
        assertThat(writeBack.getNextOverrideState(topLevelItemForState(null), true))
            .isEqualTo(ProjectOverrideState.INHERIT);
        assertThat(writeBack.getNextOverrideState(topLevelItemForState(null), false))
            .isEqualTo(ProjectOverrideState.UNLOAD);
    }

    /** user 域 top-level 项（project 覆写状态由 project settings 读出）。 */
    private ConfigItem topLevelItemForState(String ignored) {
        return userSkill("foo");
    }

    // ── setProjectTopLevelOverride（pi :677-695）─────────────────

    @Test
    void projectLoadOnInheritedItemPushesDeclarationPlusEnable() {
        // 继承的 global 条目（user 域项在 project 模式覆写）：先推无前缀声明再推 +。
        var item = userSkill("foo");

        writeBack.setProjectTopLevelOverride(item, ProjectOverrideState.LOAD, true);

        assertThat(manager.project().skills)
            .containsExactly(item.path(), "+" + item.path());
    }

    @Test
    void projectInheritClearsOverrideEntries() {
        var item = userSkill("foo");
        manager.project().skills = new java.util.ArrayList<>(
            List.of(item.path(), "+" + item.path(), "+unrelated"));

        writeBack.setProjectTopLevelOverride(item, ProjectOverrideState.INHERIT, true);

        assertThat(manager.project().skills).containsExactly("+unrelated");
    }

    @Test
    void projectOverrideOnProjectLocalItemUsesRelativePattern() {
        // project 域条目（非继承）：pattern＝相对 .pi-java 基目录。
        var path = cwd.resolve(".pi-java").resolve("skills").resolve("bar").resolve("SKILL.md");
        var item = new ConfigItem(path.toString(), true, ResourceType.SKILLS,
            new PathMetadata("local", SourceScope.PROJECT, Origin.TOP_LEVEL,
                cwd.resolve(".pi-java").toString(), null));

        writeBack.setProjectTopLevelOverride(item, ProjectOverrideState.UNLOAD, false);

        assertThat(manager.project().skills).containsExactly("-" + rel("skills", "bar", "SKILL.md"));
    }

    // ── setProjectPackageOverride（pi :704-737）──────────────────

    @Test
    void projectPackageOverrideCreatesAutoloadFalseEntry() throws IOException {
        // project packages 无此包 ⇒ 新建 {source, autoload:false} 推 +pattern。
        var root = agentDir.resolve("npm/node_modules/foo");
        Files.createDirectories(root);
        var item = packageItem("npm:foo", root, "skills/s/SKILL.md", ResourceType.SKILLS);

        var ok = writeBack.setProjectPackageOverride(item, ProjectOverrideState.LOAD);

        assertThat(ok).isTrue();
        var pkg = manager.project().packages.get(0);
        assertThat(pkg.source()).isEqualTo("npm:foo");
        assertThat(pkg.autoload()).isFalse();
        assertThat(pkg.skills()).containsExactly("+" + rel("skills", "s", "SKILL.md"));
    }

    @Test
    void projectPackageOverrideInheritWithoutPackageIsNoOp() throws IOException {
        var root = agentDir.resolve("npm/node_modules/foo");
        Files.createDirectories(root);
        var item = packageItem("npm:foo", root, "skills/s/SKILL.md", ResourceType.SKILLS);

        assertThat(writeBack.setProjectPackageOverride(item, ProjectOverrideState.INHERIT))
            .isFalse();
        assertThat(manager.project().packages).isNull();
    }

    @Test
    void projectPackageOverrideInheritRemovesAutoloadFalseEntry() throws IOException {
        var root = agentDir.resolve("npm/node_modules/foo");
        Files.createDirectories(root);
        var item = packageItem("npm:foo", root, "skills/s/SKILL.md", ResourceType.SKILLS);
        manager.project().packages = new java.util.ArrayList<>(List.of(
            PackageSource.object("npm:foo", false, null, List.of("+" + rel("skills", "s", "SKILL.md")),
                null, null)));

        var ok = writeBack.setProjectPackageOverride(item, ProjectOverrideState.INHERIT);

        assertThat(ok).isTrue();
        // 过滤全空且 autoload=false ⇒ 整条删除（pi :731-733）
        assertThat(manager.project().packages).isEmpty();
    }

    @Test
    void projectPackageOverrideCollapsesToStringWithoutAutoload() throws IOException {
        var root = agentDir.resolve("npm/node_modules/foo");
        Files.createDirectories(root);
        var item = packageItem("npm:foo", root, "skills/s/SKILL.md", ResourceType.SKILLS);
        manager.project().packages = new java.util.ArrayList<>(List.of(
            PackageSource.object("npm:foo", null, null, List.of("+" + rel("skills", "s", "SKILL.md")),
                null, null)));

        writeBack.setProjectPackageOverride(item, ProjectOverrideState.INHERIT);

        assertThat(manager.project().packages)
            .containsExactly(PackageSource.of("npm:foo"));
    }

    @Test
    void localPackageOverrideSourceIsRelativeToProjectRoot() throws IOException {
        // 本地包源：新建覆写源的 source 转相对 project 根（pi :811-816）。
        var pkgDir = agentDir.resolve("local-pkg");
        Files.createDirectories(pkgDir);
        var item = new ConfigItem(pkgDir.resolve("skills/s/SKILL.md").toString(), true,
            ResourceType.SKILLS,
            new PathMetadata("local-pkg", SourceScope.USER, Origin.PACKAGE,
                pkgDir.toString(), pkgDir.toString()));

        writeBack.setProjectPackageOverride(item, ProjectOverrideState.UNLOAD);

        var pkg = manager.project().packages.get(0);
        var expected = cwd.resolve(SourceResolver.CONFIG_DIR_NAME).toAbsolutePath().normalize()
            .relativize(pkgDir.toAbsolutePath().normalize()).toString();
        assertThat(pkg.source()).isEqualTo(expected);
        assertThat(pkg.autoload()).isFalse();
    }

    // ── 继承态读取（pi :747-780）─────────────────────────────────

    @Test
    void overrideStateReadsFromProjectEntries() {
        assertThat(writeBack.getProjectOverrideState(userSkill("foo")))
            .isEqualTo(ProjectOverrideState.INHERIT);

        manager.project().skills =
            new java.util.ArrayList<>(List.of("+" + rel("skills", "foo", "SKILL.md")));
        assertThat(writeBack.getProjectOverrideState(userSkill("foo")))
            .isEqualTo(ProjectOverrideState.LOAD);

        manager.project().skills =
            new java.util.ArrayList<>(List.of("!" + rel("skills", "foo", "SKILL.md")));
        assertThat(writeBack.getProjectOverrideState(userSkill("foo")))
            .isEqualTo(ProjectOverrideState.UNLOAD);
    }

    @Test
    void packageOverrideStateHonorsAutoloadFalseEmptyArray() throws IOException {
        var root = agentDir.resolve("npm/node_modules/foo");
        Files.createDirectories(root);
        var item = packageItem("npm:foo", root, "skills/s/SKILL.md", ResourceType.SKILLS);
        // 对象形＋autoload 缺席（≠false）＋skills 键存在但空表 ⇒ unload（pi :772；
        // emptyArrayIsUnload＝autoload !== false——autoload=false 时空表归 inherit）。
        manager.project().packages = new java.util.ArrayList<>(List.of(
            PackageSource.object("npm:foo", null, null, List.of(), null, null)));

        assertThat(writeBack.getProjectOverrideState(item))
            .isEqualTo(ProjectOverrideState.UNLOAD);
    }
}
