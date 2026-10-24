package com.pijava.coding.agent.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.pijava.coding.agent.pkg.PackageManager;
import com.pijava.coding.agent.pkg.PathMetadata;
import com.pijava.coding.agent.pkg.PathMetadata.Origin;
import com.pijava.coding.agent.pkg.ResourceType;
import com.pijava.coding.agent.pkg.SourceParser;
import com.pijava.coding.agent.pkg.SourceResolver;
import com.pijava.coding.agent.pkg.SourceScope;

/**
 * pi config TUI 的写回四函数＋三态循环（批 19 T6，docs/30 §3）——
 * {@code toggleTopLevelResource}/{@code togglePackageResource}/
 * {@code setProjectTopLevelOverride}/{@code setProjectPackageOverride}＋
 * {@code getProjectOverrideState}/{@code getNextOverrideState}/
 * {@code getOverrideStateFromEntries}（config-selector.ts:522-872）逐字对位。
 *
 * <p>全部写回走批 19 T2 的字段级 merge 面（{@link SettingsManager#setPackages} 等）。
 * 本类纯逻辑无 UI；TUI 组件（pi-java-tui {@code ConfigSelectorComponent}）把列表项
 * 包成 {@link ConfigItem} 调这里。</p>
 */
public final class ConfigResourceWriteBack {

    /** pi {@code ProjectOverrideState} 字面量闭集 ⇒ enum。 */
    public enum ProjectOverrideState {
        INHERIT, LOAD, UNLOAD;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** pi {@code ResourceItem} 的写回消费形（UI 列表项的投影）。 */
    public record ConfigItem(String path, boolean enabled, ResourceType resourceType,
            PathMetadata metadata) {

        /** pi {@code getItemScope}（:854-856）：temporary 归 user。 */
        public SourceScope itemScope() {
            return metadata.scope() == SourceScope.PROJECT ? SourceScope.PROJECT
                : SourceScope.USER;
        }

        /** pi {@code getResourceItemKey}（:850-852）：{@code type:canonicalizePath}。 */
        public String itemKey() {
            return resourceType.id() + ":" + PackageManager.canonicalizePath(path);
        }
    }

    private final SettingsManager settings;
    private final SourceResolver resolver;
    private final Path cwd;
    private final Path agentDir;

    public ConfigResourceWriteBack(SettingsManager settings, SourceResolver resolver,
            Path cwd, Path agentDir) {
        this.settings = settings;
        this.resolver = resolver;
        this.cwd = cwd;
        this.agentDir = agentDir;
    }

    // ── toggle（global 写作用域，:528-643）──────────────────────

    /** pi {@code toggleResource} 的 global 臂（:528-535）：翻转 enabled 分派两写回。 */
    public boolean toggleGlobal(ConfigItem item) {
        var enabled = !item.enabled();
        if (item.metadata().origin() == Origin.TOP_LEVEL) {
            toggleTopLevelResource(item, enabled);
        } else {
            togglePackageResource(item, enabled);
        }
        return enabled;
    }

    /**
     * pi {@code toggleResource} 的 project 臂（:523-526）：三态循环 → 覆写写回；
     * 返回新 effective enabled（inherit ⇒ 继承值），未写 ⇒ {@code empty}
     * （pi undefined；{@code Optional} 而非 {@code Boolean} null——spotbugs
     * NP_BOOLEAN_RETURN_NULL 坑，批 agent-core-4 教训）。
     */
    public java.util.Optional<Boolean> toggleProject(ConfigItem item, boolean inheritedEnabled,
            boolean inheritedGlobal) {
        var state = getNextOverrideState(item, inheritedEnabled);
        var ok = item.metadata().origin() == Origin.TOP_LEVEL
            ? setProjectTopLevelOverride(item, state, inheritedGlobal)
            : setProjectPackageOverride(item, state);
        if (!ok) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(switch (state) {
            case INHERIT -> inheritedEnabled;
            case LOAD -> true;
            case UNLOAD -> false;
        });
    }

    /** pi {@code toggleTopLevelResource}（:538-584）：剥前缀去重 → 推 {@code +/-pattern}。 */
    public void toggleTopLevelResource(ConfigItem item, boolean enabled) {
        var scope = item.itemScope();
        var current = new ArrayList<>(topLevelEntries(scope, item.resourceType()));
        var pattern = getResourcePattern(item);
        current.removeIf(entry -> getPatternEntryTarget(entry).equals(pattern));
        current.add((enabled ? "+" : "-") + pattern);
        writeTopLevel(scope, item.resourceType(), current);
    }

    /** pi {@code togglePackageResource}（:586-643）：匹配串项升级对象形推
     *  {@code +/-pattern}（相对包根）；四过滤键全空折叠回纯串（pi quirk：autoload
     *  键随之丢，钉死）。 */
    public void togglePackageResource(ConfigItem item, boolean enabled) {
        var scope = item.itemScope();
        var packages = new ArrayList<>(packagesOf(scope));
        var pkgIndex = indexOfSourceString(packages, item.metadata().source());
        if (pkgIndex < 0) {
            return;
        }
        var pkg = upgradeToObject(packages, pkgIndex);
        var pattern = getPackageResourcePattern(item);
        var updated = new ArrayList<>(filterList(pkg, item.resourceType()));
        updated.removeIf(entry -> getPatternEntryTarget(entry).equals(pattern));
        updated.add((enabled ? "+" : "-") + pattern);
        pkg = withFilter(pkg, item.resourceType(), updated.isEmpty() ? null : updated);
        packages.set(pkgIndex, hasFilters(pkg) ? pkg : PackageSource.of(pkg.source()));
        writePackages(scope, packages);
    }

    // ── project 三态覆写（:671-745）─────────────────────────────

    /** pi {@code setProjectTopLevelOverride}（:677-695）：非 inherit 先推无前缀
     *  声明条目（继承条目、builtin 除外）再推 {@code +/-}；inherit 清条目。 */
    public boolean setProjectTopLevelOverride(ConfigItem item, ProjectOverrideState state,
            boolean inheritedGlobal) {
        var current = new ArrayList<>(topLevelEntries(SourceScope.PROJECT, item.resourceType()));
        var pattern = inheritedGlobal ? item.path()
            : getResourcePatternForScope(item, SourceScope.PROJECT);
        var patterns = getTopLevelOverridePatterns(item, SourceScope.PROJECT);
        var updated = new ArrayList<String>();
        for (var entry : current) {
            var target = getPatternEntryTarget(entry);
            if (isPrefixed(entry) && patterns.contains(target)) {
                continue;
            }
            if (state == ProjectOverrideState.INHERIT && inheritedGlobal
                && target.equals(pattern)) {
                continue;
            }
            updated.add(entry);
        }
        if (state != ProjectOverrideState.INHERIT) {
            // Project entries name inherited files to override them. Built-in paths
            // need no entry.（pi :688）
            if (inheritedGlobal && !"builtin".equals(item.metadata().source())
                && !updated.contains(pattern)) {
                updated.add(pattern);
            }
            updated.add((state == ProjectOverrideState.LOAD ? "+" : "-") + pattern);
        }
        writeTopLevel(SourceScope.PROJECT, item.resourceType(), updated);
        return true;
    }

    /** pi {@code setProjectPackageOverride}（:704-737）：找不到包新建
     *  {@code {source, autoload:false}}（本地源转相对 project 根）；过滤全空且
     *  autoload=false ⇒ 整条删除，否则折叠回纯串。 */
    public boolean setProjectPackageOverride(ConfigItem item, ProjectOverrideState state) {
        var packages = new ArrayList<>(packagesOf(SourceScope.PROJECT));
        var pkgIndex = indexOfMatchingPackage(packages, item, SourceScope.PROJECT);
        if (pkgIndex < 0) {
            if (state == ProjectOverrideState.INHERIT) {
                return false;
            }
            packages.add(createPackageOverrideSource(item));
            pkgIndex = packages.size() - 1;
        }
        var pkg = upgradeToObject(packages, pkgIndex);
        var pattern = getPackageResourcePattern(item);
        var updated = new ArrayList<>(filterList(pkg, item.resourceType()));
        updated.removeIf(entry -> getPatternEntryTarget(entry).equals(pattern));
        if (state != ProjectOverrideState.INHERIT) {
            updated.add((state == ProjectOverrideState.LOAD ? "+" : "-") + pattern);
        }
        pkg = withFilter(pkg, item.resourceType(), updated.isEmpty() ? null : updated);
        if (!hasFilters(pkg)) {
            if (Boolean.FALSE.equals(pkg.autoload())) {
                packages.remove(pkgIndex);
            } else {
                packages.set(pkgIndex, PackageSource.of(pkg.source()));
            }
        } else {
            packages.set(pkgIndex, pkg);
        }
        settings.setProjectPackages(packages);
        return true;
    }

    /** pi {@code getNextOverrideState}（:739-745）真值表逐字。 */
    public ProjectOverrideState getNextOverrideState(ConfigItem item, boolean inheritedEnabled) {
        var state = getProjectOverrideState(item);
        return switch (state) {
            case INHERIT -> inheritedEnabled ? ProjectOverrideState.UNLOAD : ProjectOverrideState.LOAD;
            case UNLOAD -> inheritedEnabled ? ProjectOverrideState.LOAD : ProjectOverrideState.INHERIT;
            case LOAD -> inheritedEnabled ? ProjectOverrideState.INHERIT : ProjectOverrideState.UNLOAD;
        };
    }

    /** pi {@code getProjectOverrideState}（:747-765）：top-level 看 project 四类
     *  数组；package 看 project packages 对象形条目（autoload=false＋空表 ⇒ unload）。 */
    public ProjectOverrideState getProjectOverrideState(ConfigItem item) {
        if (item.metadata().origin() == Origin.TOP_LEVEL) {
            return getOverrideStateFromEntries(
                topLevelEntries(SourceScope.PROJECT, item.resourceType()),
                getTopLevelOverridePatterns(item, SourceScope.PROJECT), false);
        }
        var pkg = findMatchingPackageSource(item, SourceScope.PROJECT);
        if (pkg == null || pkg.stringForm()) {
            return ProjectOverrideState.INHERIT;
        }
        var entries = filterList(pkg, item.resourceType());
        if (filterArrayAbsent(pkg, item.resourceType())) {
            return ProjectOverrideState.INHERIT;
        }
        return getOverrideStateFromEntries(entries,
            Set.of(getPackageResourcePattern(item)), !Boolean.FALSE.equals(pkg.autoload()));
    }

    /** pi {@code getOverrideStateFromEntries}（:767-780）：{@code !}/{@code -} ⇒
     *  unload，其余命中 ⇒ load；后写压前写；空表＋emptyArrayIsUnload ⇒ unload。 */
    ProjectOverrideState getOverrideStateFromEntries(List<String> entries, Set<String> patterns,
            boolean emptyArrayIsUnload) {
        if (entries.isEmpty() && emptyArrayIsUnload) {
            return ProjectOverrideState.UNLOAD;
        }
        var state = ProjectOverrideState.INHERIT;
        for (var entry : entries) {
            if (!patterns.contains(getPatternEntryTarget(entry))) {
                continue;
            }
            state = entry.startsWith("!") || entry.startsWith("-")
                ? ProjectOverrideState.UNLOAD : ProjectOverrideState.LOAD;
        }
        return state;
    }

    // ── pattern 生成（:793-872）─────────────────────────────────

    /** pi {@code getResourcePattern}（:862-867）：builtin＝完整 path；否则相对
     *  {@code metadata.baseDir ?? 默认基目录}。 */
    public String getResourcePattern(ConfigItem item) {
        if ("builtin".equals(item.metadata().source())) {
            return item.path();
        }
        var baseDir = item.metadata().baseDir() != null
            ? Path.of(item.metadata().baseDir()) : topLevelBaseDir(item.itemScope());
        return relative(baseDir, item.path());
    }

    /** pi {@code getResourcePatternForScope}（:804-809）。 */
    public String getResourcePatternForScope(ConfigItem item, SourceScope scope) {
        if (scope != item.itemScope() || "builtin".equals(item.metadata().source())) {
            return item.path();
        }
        var baseDir = item.metadata().baseDir() != null
            ? Path.of(item.metadata().baseDir()) : topLevelBaseDir(item.itemScope());
        return relative(baseDir, item.path());
    }

    /** pi {@code getPackageResourcePattern}（:869-872）：相对包根（baseDir 缺席
     *  ⇒ 文件父目录）。 */
    public String getPackageResourcePattern(ConfigItem item) {
        var baseDir = item.metadata().baseDir() != null
            ? item.metadata().baseDir() : dirname(item.path());
        return relative(Path.of(baseDir), item.path());
    }

    /** pi {@code getTopLevelOverridePatterns}（:793-802）。 */
    Set<String> getTopLevelOverridePatterns(ConfigItem item, SourceScope scope) {
        var patterns = new LinkedHashSet<String>();
        patterns.add(getResourcePatternForScope(item, scope));
        patterns.add(item.path());
        patterns.add(relative(topLevelBaseDir(scope), item.path()));
        if (item.metadata().baseDir() != null) {
            patterns.add(relative(Path.of(item.metadata().baseDir()), item.path()));
        }
        return patterns;
    }

    // ── 小件 ────────────────────────────────────────────────────

    /** pi {@code getPatternEntryTarget}（:846-848）：剥 {@code !}/{@code +}/{@code -}。 */
    static String getPatternEntryTarget(String entry) {
        return isPrefixed(entry) ? entry.substring(1) : entry;
    }

    private static boolean isPrefixed(String entry) {
        return entry.startsWith("!") || entry.startsWith("+") || entry.startsWith("-");
    }

    /** pi {@code getTopLevelBaseDir}（:858-860）。 */
    private Path topLevelBaseDir(SourceScope scope) {
        return scope == SourceScope.PROJECT
            ? cwd.resolve(SourceResolver.CONFIG_DIR_NAME) : agentDir;
    }

    private List<String> topLevelEntries(SourceScope scope, ResourceType type) {
        var scopeSettings = scope == SourceScope.PROJECT ? settings.project() : settings.global();
        var entries = switch (type) {
            case EXTENSIONS -> scopeSettings.extensions;
            case SKILLS -> scopeSettings.skills;
            case PROMPTS -> scopeSettings.prompts;
            case THEMES -> scopeSettings.themes;
        };
        return entries == null ? List.of() : entries;
    }

    private void writeTopLevel(SourceScope scope, ResourceType type, List<String> values) {
        if (scope == SourceScope.PROJECT) {
            switch (type) {
                case EXTENSIONS -> settings.setProjectExtensions(values);
                case SKILLS -> settings.setProjectSkills(values);
                case PROMPTS -> settings.setProjectPrompts(values);
                case THEMES -> settings.setProjectThemes(values);
            }
            return;
        }
        switch (type) {
            case EXTENSIONS -> settings.setExtensions(values);
            case SKILLS -> settings.setSkills(values);
            case PROMPTS -> settings.setPrompts(values);
            case THEMES -> settings.setThemes(values);
        }
    }

    private List<PackageSource> packagesOf(SourceScope scope) {
        var packages = scope == SourceScope.PROJECT
            ? settings.project().packages : settings.global().packages;
        return packages == null ? List.of() : packages;
    }

    private void writePackages(SourceScope scope, List<PackageSource> packages) {
        if (scope == SourceScope.PROJECT) {
            settings.setProjectPackages(packages);
        } else {
            settings.setPackages(packages);
        }
    }

    /** pi togglePackageResource 的查找（:592-595）：裸串等值（非身份匹配）。 */
    private static int indexOfSourceString(List<PackageSource> packages, String source) {
        for (int i = 0; i < packages.size(); i++) {
            if (packages.get(i).source().equals(source)) {
                return i;
            }
        }
        return -1;
    }

    /** pi findMatchingPackageSource 的查找（:831-844）：scope 感知的串匹配。 */
    private int indexOfMatchingPackage(List<PackageSource> packages, ConfigItem item,
            SourceScope targetScope) {
        for (int i = 0; i < packages.size(); i++) {
            if (packageSourceStringMatches(item.metadata().source(), item.itemScope(),
                packages.get(i).source(), targetScope)) {
                return i;
            }
        }
        return -1;
    }

    private PackageSource findMatchingPackageSource(ConfigItem item, SourceScope targetScope) {
        var packages = packagesOf(targetScope);
        var index = indexOfMatchingPackage(packages, item, targetScope);
        return index < 0 ? null : packages.get(index);
    }

    /** pi {@code packageSourceStringMatches}（:818-829）：串等值 ⇒ true；双方均
     *  local ⇒ 各自相对基目录解析后比绝对串。 */
    boolean packageSourceStringMatches(String leftSource, SourceScope leftScope,
            String rightSource, SourceScope rightScope) {
        if (leftSource.equals(rightSource)) {
            return true;
        }
        if (!SourceParser.isLocalPath(leftSource) || !SourceParser.isLocalPath(rightSource)) {
            return false;
        }
        var left = resolver.resolvePathFromBase(leftSource, topLevelBaseDir(leftScope));
        var right = resolver.resolvePathFromBase(rightSource, topLevelBaseDir(rightScope));
        return left.equals(right);
    }

    /** pi {@code createPackageOverrideSource}（:811-816）：本地源转相对 project
     *  根（空 ⇒ {@code "."}），{@code autoload:false}。 */
    private PackageSource createPackageOverrideSource(ConfigItem item) {
        var source = item.metadata().source();
        if (!SourceParser.isLocalPath(source)) {
            return PackageSource.object(source, false, null, null, null, null);
        }
        var sourcePath = resolver.resolvePathFromBase(source, topLevelBaseDir(item.itemScope()));
        var rel = relative(topLevelBaseDir(SourceScope.PROJECT), sourcePath);
        return PackageSource.object(rel.isEmpty() ? "." : rel, false, null, null, null, null);
    }

    /** pi 的 string ⇒ object 升级（:602-605/:721-724）。 */
    private static PackageSource upgradeToObject(List<PackageSource> packages, int index) {
        var pkg = packages.get(index);
        if (pkg.stringForm()) {
            pkg = PackageSource.object(pkg.source(), null, null, null, null, null);
            packages.set(index, pkg);
        }
        return pkg;
    }

    private static List<String> filterList(PackageSource pkg, ResourceType type) {
        var list = switch (type) {
            case EXTENSIONS -> pkg.extensions();
            case SKILLS -> pkg.skills();
            case PROMPTS -> pkg.prompts();
            case THEMES -> pkg.themes();
        };
        return list == null ? List.of() : list;
    }

    /** pi {@code pkg[arrayKey] === undefined} 判定。 */
    private static boolean filterArrayAbsent(PackageSource pkg, ResourceType type) {
        return switch (type) {
            case EXTENSIONS -> pkg.extensions() == null;
            case SKILLS -> pkg.skills() == null;
            case PROMPTS -> pkg.prompts() == null;
            case THEMES -> pkg.themes() == null;
        };
    }

    private static PackageSource withFilter(PackageSource pkg, ResourceType type,
            List<String> values) {
        return switch (type) {
            case EXTENSIONS -> PackageSource.object(pkg.source(), pkg.autoload(), values,
                pkg.skills(), pkg.prompts(), pkg.themes());
            case SKILLS -> PackageSource.object(pkg.source(), pkg.autoload(), pkg.extensions(),
                values, pkg.prompts(), pkg.themes());
            case PROMPTS -> PackageSource.object(pkg.source(), pkg.autoload(), pkg.extensions(),
                pkg.skills(), values, pkg.themes());
            case THEMES -> PackageSource.object(pkg.source(), pkg.autoload(), pkg.extensions(),
                pkg.skills(), pkg.prompts(), values);
        };
    }

    /** pi {@code hasFilters}（:631-633/:731）：四过滤键任一非空。 */
    private static boolean hasFilters(PackageSource pkg) {
        return pkg.extensions() != null || pkg.skills() != null
            || pkg.prompts() != null || pkg.themes() != null;
    }

    /** node {@code path.relative} 对位（跨根/非法字符回落目标串——pi 纯串运算不抛）。 */
    private static String relative(Path baseDir, String filePath) {
        try {
            return baseDir.toAbsolutePath().normalize()
                .relativize(Path.of(filePath).toAbsolutePath().normalize())
                .toString();
        } catch (IllegalArgumentException e) {
            // 含 InvalidPathException（子类——multi-catch 不能并列父子）。
            return filePath;
        }
    }

    /** node {@code path.dirname} 对位（纯串，兼容两向分隔符）。 */
    private static String dirname(String path) {
        var idx = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return idx < 0 ? path : path.substring(0, idx);
    }
}
