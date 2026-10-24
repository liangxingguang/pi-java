package com.pijava.tui.component.config;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

import com.pijava.coding.agent.core.ConfigResourceWriteBack;
import com.pijava.coding.agent.core.InMemorySettingsStorage;
import com.pijava.coding.agent.core.SettingsManager;
import com.pijava.coding.agent.pkg.PathMetadata;
import com.pijava.coding.agent.pkg.PathMetadata.Origin;
import com.pijava.coding.agent.pkg.ResolvedPaths;
import com.pijava.coding.agent.pkg.ResolvedResource;
import com.pijava.coding.agent.pkg.ResourceType;
import com.pijava.coding.agent.pkg.SourceResolver;
import com.pijava.coding.agent.pkg.SourceScope;
import com.pijava.coding.agent.spi.ConfigSelectorProvider.WriteScope;
import com.pijava.tui.component.config.ConfigSelectorModel.FlatEntry;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;

/**
 * 批 19 T6（docs/30 §7）：config TUI 本体——分组标签/排序/显示名规则、键位表
 * （跳标题/pageUp 吸附/tab 门）、复选框三态渲染与后缀逐字、搜索过滤保留 group 头、
 * 空态、toggle 写穿 settings。
 */
class ConfigSelectorScreenTest {

    @TempDir
    Path agentDir;

    @TempDir
    Path cwd;

    @TempDir
    Path homeDir;

    private SettingsManager settings;
    private ConfigResourceWriteBack writeBack;

    @BeforeEach
    void setUp() {
        settings = SettingsManager.withStorage(new InMemorySettingsStorage());
        var resolver = new SourceResolver(cwd, agentDir, settings::isProjectTrusted);
        writeBack = new ConfigResourceWriteBack(settings, resolver, cwd, agentDir);
    }

    // ── 夹具构造 ────────────────────────────────────────────────

    private static ResolvedResource resource(String path, boolean enabled,
            PathMetadata metadata) {
        return new ResolvedResource(path, enabled, metadata);
    }

    private static PathMetadata topLevel(String source, SourceScope scope, String baseDir) {
        return new PathMetadata(source, scope, Origin.TOP_LEVEL, baseDir, null);
    }

    /** 全域夹具：内置扩展＋包资源＋user settings 条目＋自动发现 skill＋project 条目。 */
    private ResolvedPaths globalPaths() {
        var pkgRoot = agentDir.resolve("npm/node_modules/foo");
        return new ResolvedPaths(
            List.of(
                resource("builtin:mcp", true,
                    topLevel("builtin", SourceScope.USER, null)),
                // 嵌套目录（父目录 ≠ "extensions"）⇒ 显示名「父目录/文件名」臂。
                resource(pkgRoot.resolve("extensions/tool.jar").toString(), true,
                    new PathMetadata("npm:foo", SourceScope.USER, Origin.PACKAGE,
                        pkgRoot.toString(), pkgRoot.toString())),
                resource(pkgRoot.resolve("examples/nested-tool.jar").toString(), true,
                    new PathMetadata("npm:foo", SourceScope.USER, Origin.PACKAGE,
                        pkgRoot.toString(), pkgRoot.toString())),
                resource(agentDir.resolve("extensions/my-ext.jar").toString(), false,
                    topLevel("local", SourceScope.USER, agentDir.toString()))),
            List.of(resource(agentDir.resolve("skills").resolve("review").resolve("SKILL.md")
                    .toString(), true,
                new PathMetadata("auto", SourceScope.USER, Origin.TOP_LEVEL,
                    agentDir.toString(), null))),
            List.of(),
            List.of());
    }

    private ResolvedPaths projectPaths() {
        var global = globalPaths();
        var projectSkill = cwd.resolve(".pi-java/skills/local-skill/SKILL.md");
        return new ResolvedPaths(
            global.extensions(),
            concat(global.skills(),
                resource(projectSkill.toString(), true,
                    topLevel("local", SourceScope.PROJECT, cwd.resolve(".pi-java").toString()))),
            global.prompts(),
            global.themes());
    }

    private static List<ResolvedResource> concat(List<ResolvedResource> base,
            ResolvedResource extra) {
        var out = new java.util.ArrayList<>(base);
        out.add(extra);
        return out;
    }

    private ConfigSelectorComponent component(WriteScope scope, boolean projectAvailable) {
        var model = new ConfigSelectorModel(
            ConfigSelectorModel.buildGroups(globalPaths(), agentDir),
            ConfigSelectorModel.buildGroups(projectPaths(), agentDir),
            scope);
        return new ConfigSelectorComponent(model, writeBack, 24, projectAvailable);
    }

    private static List<String> texts(ConfigSelectorComponent c, int width) {
        return c.layout(width).stream()
            .map(row -> row.stream().map(ConfigSelectorComponent.RowSpan::text)
                .reduce("", String::concat))
            .toList();
    }

    // ── 分组与显示名（pi :84-186）───────────────────────────────

    @Test
    void groupLabelsAndOrder() {
        var groups = ConfigSelectorModel.buildGroups(globalPaths(), agentDir);
        var labels = groups.stream().map(ConfigSelectorModel.ResourceGroup::label).toList();

        // 排序：package 先于 top-level；user 先于 project（pi :164-174）
        assertThat(labels.get(0)).isEqualTo("npm:foo (user)");
        assertThat(labels).contains("Built-in");
        assertThat(labels).contains("User settings");
        // 自动发现带 baseDir ⇒ User (<baseDir>/)
        assertThat(labels).anyMatch(l -> l.startsWith("User (") && l.endsWith("/)"));
    }

    @Test
    void displayNameRules() {
        var groups = ConfigSelectorModel.buildGroups(globalPaths(), agentDir);
        var names = groups.stream()
            .flatMap(g -> g.subgroups().stream())
            .flatMap(sg -> sg.items().stream())
            .map(ConfigSelectorModel.ResourceItem::displayName)
            .toList();

        assertThat(names).contains("mcp");                  // builtin 去前缀
        assertThat(names).contains("examples/nested-tool.jar");   // 父目录≠extensions ⇒ 父/名
        assertThat(names).contains("tool.jar");              // 父目录＝extensions ⇒ 裸文件名
        assertThat(names).contains("review");                // SKILL.md ⇒ 父目录名
        assertThat(names).contains("my-ext.jar");            // extensions 直属 ⇒ 文件名
    }

    // ── 渲染（pi :208-225/:398-458）──────────────────────────────

    @Test
    void headerRowsVerbatim() {
        var lines = texts(component(WriteScope.GLOBAL, true), 100);

        assertThat(lines).anyMatch(l -> l.startsWith("Global Resources")
            && l.contains("tab switch mode · space toggle · esc close"));
        assertThat(lines).contains("~/.pi-java/agent/settings.json");
    }

    @Test
    void projectHeaderRowsVerbatim() {
        var lines = texts(component(WriteScope.PROJECT, true), 100);

        assertThat(lines).anyMatch(l -> l.startsWith("Project Local Resources")
            && l.contains("space cycle inherit/+/-"));
        assertThat(lines)
            .contains(".pi-java/settings.json · inherited global resources are dimmed");
    }

    @Test
    void projectHeaderOmitsTabHintWhenUnavailable() {
        var lines = texts(component(WriteScope.PROJECT, false), 100);

        assertThat(lines).noneMatch(l -> l.contains("tab switch mode"));
    }

    @Test
    void globalCheckboxesAndGrouping() {
        var lines = texts(component(WriteScope.GLOBAL, true), 100);

        assertThat(lines).anyMatch(l -> l.contains("[x] mcp"));
        assertThat(lines).anyMatch(l -> l.contains("[ ] my-ext.jar"));
        assertThat(lines).anyMatch(l -> l.trim().equals("Skills"));   // subgroup 头
        assertThat(lines).anyMatch(l -> l.contains("[x] review"));
    }

    @Test
    void projectModeRendersOverrideStates() {
        // project 覆写态：user 条目继承（dim 后缀）＋project 条目可覆写。
        settings.project().skills = new java.util.ArrayList<>(
            List.of("+" + agentDir.resolve("skills/review/SKILL.md")));
        var lines = texts(component(WriteScope.PROJECT, true), 120);

        assertThat(lines).anyMatch(l -> l.contains("[+] review")
            && l.contains("  project load"));
        assertThat(lines).anyMatch(l -> l.contains("local-skill"));
    }

    @Test
    void inheritedGlobalSuffixShownInProjectMode() {
        var lines = texts(component(WriteScope.PROJECT, true), 120);

        assertThat(lines).anyMatch(l -> l.contains("mcp") && l.contains("  inherited global"));
        assertThat(lines).anyMatch(l -> l.contains("Built-in · inherited global"));
    }

    @Test
    void emptyStateVerbatim() {
        var model = new ConfigSelectorModel(List.of(), List.of(), WriteScope.GLOBAL);
        var c = new ConfigSelectorComponent(model, writeBack, 24, true);

        assertThat(texts(c, 80)).contains("  No resources found");
    }

    // ── 键位表（pi :460-520）────────────────────────────────────

    @Test
    void navigationSkipsHeaders() {
        var c = component(WriteScope.GLOBAL, true);
        var first = c.model().selectedIndex();
        assertThat(c.model().filteredItems().get(first))
            .isInstanceOf(FlatEntry.ItemRow.class);

        c.onKeyEvent(KeyEvent.ofKey(KeyCode.DOWN));
        var second = c.model().selectedIndex();
        assertThat(second).isGreaterThan(first);
        assertThat(c.model().filteredItems().get(second))
            .isInstanceOf(FlatEntry.ItemRow.class);

        c.onKeyEvent(KeyEvent.ofKey(KeyCode.UP));
        assertThat(c.model().selectedIndex()).isEqualTo(first);
    }

    @Test
    void pageDownSnapsToNearestItem() {
        var c = component(WriteScope.GLOBAL, true);
        var count = c.model().filteredItems().size();

        c.onKeyEvent(KeyEvent.ofKey(KeyCode.PAGE_DOWN));

        var idx = c.model().selectedIndex();
        assertThat(c.model().filteredItems().get(idx)).isInstanceOf(FlatEntry.ItemRow.class);
        assertThat(idx).isLessThanOrEqualTo(Math.min(count - 1, 16));
    }

    @Test
    void tabSwitchesScopeOnlyWhenAvailable() {
        var c = component(WriteScope.GLOBAL, true);
        c.onKeyEvent(KeyEvent.ofKey(KeyCode.TAB));
        assertThat(c.model().writeScope()).isEqualTo(WriteScope.PROJECT);
        c.onKeyEvent(KeyEvent.ofKey(KeyCode.TAB));
        assertThat(c.model().writeScope()).isEqualTo(WriteScope.GLOBAL);

        var gated = component(WriteScope.GLOBAL, false);
        gated.onKeyEvent(KeyEvent.ofKey(KeyCode.TAB));
        assertThat(gated.model().writeScope()).isEqualTo(WriteScope.GLOBAL);
    }

    @Test
    void escapeClosesAndCtrlCExits() {
        var c = component(WriteScope.GLOBAL, true);
        c.onKeyEvent(KeyEvent.ofKey(KeyCode.ESCAPE));
        assertThat(c.isDone()).isTrue();

        var c2 = component(WriteScope.GLOBAL, true);
        c2.onKeyEvent(new KeyEvent(KeyCode.CHAR, KeyModifiers.CTRL, 'c'));
        assertThat(c2.isDone()).isTrue();
    }

    // ── 搜索（pi :326-375）──────────────────────────────────────

    @Test
    void searchFiltersKeepingGroupHeaders() {
        var c = component(WriteScope.GLOBAL, true);
        for (var ch : "review".toCharArray()) {
            c.onKeyEvent(KeyEvent.ofChar(ch));
        }

        var entries = c.model().filteredItems();
        var items = entries.stream().filter(e -> e instanceof FlatEntry.ItemRow).toList();
        assertThat(items).hasSize(1);
        assertThat(((FlatEntry.ItemRow) items.get(0)).item().displayName()).isEqualTo("review");
        // group/subgroup 头保留
        assertThat(entries.stream().filter(e -> e instanceof FlatEntry.GroupRow).count())
            .isEqualTo(1);
        assertThat(entries.stream().filter(e -> e instanceof FlatEntry.SubgroupRow).count())
            .isEqualTo(1);
    }

    // ── toggle 写穿（pi :505-536）───────────────────────────────

    @Test
    void spaceTogglesGlobalItemAndWritesSettings() {
        var c = component(WriteScope.GLOBAL, true);
        // 选中 my-ext.jar（disabled）：导航到它
        while (c.model().selectedItem() == null
            || !c.model().selectedItem().displayName().equals("my-ext.jar")) {
            c.onKeyEvent(KeyEvent.ofKey(KeyCode.DOWN));
        }

        c.onKeyEvent(KeyEvent.ofChar(' '));

        // enable ⇒ +pattern 写回 global settings（pi toggleTopLevelResource；
        // 相对 agentDir，Windows 下反斜杠同 node path.relative）
        assertThat(settings.global().extensions)
            .containsExactly("+" + Path.of("extensions", "my-ext.jar").toString());
    }

    @Test
    void toggleKeepsCursorOnToggledRow() {
        // 评审 I-2：pi updateItem 原地变异不动 selectedIndex——toggle 后光标
        // 留在被拨行（不得吸附回首项）。
        var c = component(WriteScope.GLOBAL, true);
        while (c.model().selectedItem() == null
            || !c.model().selectedItem().displayName().equals("my-ext.jar")) {
            c.onKeyEvent(KeyEvent.ofKey(KeyCode.DOWN));
        }
        var indexBefore = c.model().selectedIndex();

        c.onKeyEvent(KeyEvent.ofChar(' '));

        assertThat(c.model().selectedIndex()).isEqualTo(indexBefore);
        assertThat(c.model().selectedItem().displayName()).isEqualTo("my-ext.jar");
        assertThat(c.model().selectedItem().enabled()).isTrue();
    }
}
