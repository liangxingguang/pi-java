package com.pijava.coding.agent.pkg;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

import com.pijava.coding.agent.core.PackageSource;
import com.pijava.coding.agent.core.Settings;
import com.pijava.coding.agent.core.SettingsManager;
import com.pijava.coding.agent.pkg.ParsedSource.GitSource;
import com.pijava.coding.agent.pkg.ParsedSource.LocalSource;
import com.pijava.coding.agent.pkg.ParsedSource.NpmSource;
import com.pijava.coding.agent.pkg.PathMetadata.Origin;
import com.pijava.coding.agent.pkg.ProgressEvent.Action;
import com.pijava.coding.agent.pkg.ResourceAccumulator.Slot;
import com.pijava.coding.agent.pkg.SourceResolver.ScopedPackage;

/**
 * pi {@code DefaultPackageManager} 的 resolve 内核（package-manager.ts:920-989/
 * :1296-1340 等）——包源（project 先入＋身份去重）→ settings 四类数组 →
 * 自动发现 → 内置扩展，输出五档优先级排序＋canonicalize 去重的路径表。
 *
 * <p>CLI 子命令面（install/remove/update/list/config）与 settings 写回链归
 * 批 19（docs/29 §4）；本类含 resolve 缺包臂 {@code installParsedSource} 与
 * {@code withProgress}（E6-9 发射面，裁决⑪）。</p>
 */
public final class PackageManager {

    /** pi {@code BUILTIN_PATH_PREFIX}（source-info.ts:15）。 */
    public static final String BUILTIN_PATH_PREFIX = "builtin:";

    private final SourceResolver resolver;
    private final SettingsManager settings;
    private final List<String> builtinExtensions;
    private final NpmResolver npmResolver;
    private final InstallLayout layout;
    private final GitPackageStore gitStore;
    private final PackageResourceCollector collector;
    private final Path homeDir;
    private volatile ProgressCallback progressCallback;

    public PackageManager(SourceResolver resolver, SettingsManager settings,
            List<String> builtinExtensions, NpmResolver npmResolver) {
        this(resolver, settings, builtinExtensions, npmResolver,
            new InstallLayout(resolver),
            new GitPackageStore(new GitRunner(), new InstallLayout(resolver), null),
            Path.of(PackageAutoDiscovery.getHomeDir()));
    }

    /** 全注入形（测试；{@code homeDir} 隔离真 home 的 {@code ~/.agents} 扫描）。 */
    public PackageManager(SourceResolver resolver, SettingsManager settings,
            List<String> builtinExtensions, NpmResolver npmResolver,
            InstallLayout layout, GitPackageStore gitStore, Path homeDir) {
        this.resolver = resolver;
        this.settings = settings;
        this.builtinExtensions = List.copyOf(builtinExtensions);
        this.npmResolver = npmResolver;
        this.layout = layout;
        this.gitStore = gitStore;
        this.collector = new PackageResourceCollector(resolver);
        this.homeDir = homeDir;
    }

    /** pi {@code setProgressCallback}（:833-835）。 */
    public void setProgressCallback(ProgressCallback callback) {
        this.progressCallback = callback;
        gitStore.setProgressCallback(callback);
    }

    public InstallLayout layout() {
        return layout;
    }

    // ── resolve（:920-989）───────────────────────────────────────

    /** pi {@code resolve()} 逐字总装。 */
    public ResolvedPaths resolve(MissingSourceHandler onMissing) {
        var accumulator = new ResourceAccumulator();
        var globalSettings = settings.global();
        var projectSettings = settings.project();

        // Collect all packages with scope (project first so cwd resources win collisions)
        var allPackages = new ArrayList<ScopedPackage>();
        if (projectSettings.packages != null) {
            for (var pkg : projectSettings.packages) {
                allPackages.add(new ScopedPackage(pkg, SourceScope.PROJECT));
            }
        }
        if (globalSettings.packages != null) {
            for (var pkg : globalSettings.packages) {
                allPackages.add(new ScopedPackage(pkg, SourceScope.USER));
            }
        }

        // Dedupe: project scope wins over global for same package identity
        var packageSources = resolver.dedupePackages(allPackages);
        resolvePackageSources(packageSources, accumulator, onMissing);

        var globalBaseDir = resolver.agentDir();
        var projectBaseDir = resolver.cwd().resolve(SourceResolver.CONFIG_DIR_NAME);

        for (var resourceType : ResourceType.ALL) {
            resolveLocalEntriesSafe(orEmpty(settingsList(projectSettings, resourceType)),
                resourceType, accumulator,
                new PathMetadata("local", SourceScope.PROJECT, Origin.TOP_LEVEL, null, null),
                projectBaseDir);
            resolveLocalEntriesSafe(orEmpty(settingsList(globalSettings, resourceType)),
                resourceType, accumulator,
                new PathMetadata("local", SourceScope.USER, Origin.TOP_LEVEL, null, null),
                globalBaseDir);
        }

        PackageAutoDiscovery.addAutoDiscoveredResources(accumulator, globalSettings,
            projectSettings, globalBaseDir, projectBaseDir, resolver.cwd(), homeDir,
            settings::isProjectTrusted);

        // Built-in extensions are enabled unless the user `extensions` setting excludes them,
        // for example with `-builtin:mcp`. A matching `+`, `-`, or `!` entry in the project
        // setting overrides that.
        for (var name : builtinExtensions) {
            var path = BUILTIN_PATH_PREFIX + name;
            var projectEnabled = ResourcePatterns.applyAutoloadDisabledPatterns(
                List.of(path),
                ResourcePatterns.getOverridePatterns(orEmpty(projectSettings.extensions)),
                projectBaseDir.toString()).get(path);
            accumulator.addResource(ResourceType.EXTENSIONS, path,
                new PathMetadata("builtin",
                    projectEnabled == null ? SourceScope.USER : SourceScope.PROJECT,
                    Origin.TOP_LEVEL, null, null),
                projectEnabled != null ? projectEnabled
                    : ResourcePatterns.isEnabledByOverrides(path,
                        orEmpty(globalSettings.extensions), globalBaseDir.toString()));
        }

        return toResolvedPaths(accumulator);
    }

    /** pi {@code resolveExtensionSources}（:991-1007）：CLI 显式源（{@code -e}），
     *  {@code builtin:} 前缀直收，余者按包源解析（temporary 不写 settings）。 */
    public ResolvedPaths resolveExtensionSources(List<String> sources, boolean local,
            boolean temporary) {
        var accumulator = new ResourceAccumulator();
        var scope = temporary ? SourceScope.TEMPORARY
            : local ? SourceScope.PROJECT : SourceScope.USER;
        for (var source : sources) {
            if (source.startsWith(BUILTIN_PATH_PREFIX)) {
                accumulator.addResource(ResourceType.EXTENSIONS, source,
                    new PathMetadata("builtin", scope, Origin.TOP_LEVEL, null, null), true);
            }
        }
        var packageSources = sources.stream()
            .filter(source -> !source.startsWith(BUILTIN_PATH_PREFIX))
            .map(source -> new ScopedPackage(PackageSource.of(source), scope))
            .toList();
        resolvePackageSources(packageSources, accumulator, null);
        return toResolvedPaths(accumulator);
    }

    /** pi {@code getInstalledPath}（:883-897）：已装路径查询（不存在 ⇒ null）。 */
    public Path getInstalledPath(String source, SourceScope scope) {
        var parsed = SourceParser.parseSource(source);
        if (parsed instanceof NpmSource npm) {
            var path = layout.npmInstallPath(npm, scope);
            return Files.exists(path) ? path : null;
        }
        if (parsed instanceof GitSource git) {
            var path = layout.gitInstallPath(git, scope);
            return Files.exists(path) ? path : null;
        }
        if (parsed instanceof LocalSource local) {
            var baseDir = resolver.getBaseDirForScope(scope);
            var path = Path.of(resolver.resolvePathFromBase(local.path(), baseDir));
            return Files.exists(path) ? path : null;
        }
        return null;
    }

    // ── 包源解析（:1296-1340）─────────────────────────────────────

    private void resolvePackageSources(List<ScopedPackage> sources,
            ResourceAccumulator accumulator, MissingSourceHandler onMissing) {
        for (var entry : sources) {
            var sourceStr = SourceParser.getPackageSourceString(entry.pkg());
            var filter = entry.pkg().stringForm() ? null : PackageFilter.from(entry.pkg());
            var deltaBase = findAutoloadDeltaBase(entry.pkg(), entry.scope(), sources);
            var resolvedSource = deltaBase != null ? deltaBase.source() : sourceStr;
            var resolvedScope = deltaBase != null ? deltaBase.scope() : entry.scope();
            var parsed = SourceParser.parseSource(resolvedSource);
            var metadata = new PathMetadata(sourceStr, entry.scope(), Origin.PACKAGE, null, null);

            if (parsed instanceof LocalSource local) {
                var baseDir = resolver.getBaseDirForScope(resolvedScope);
                collector.resolveLocalExtensionSource(local, accumulator, filter, metadata, baseDir);
                continue;
            }

            if (parsed instanceof NpmSource npm) {
                var installedPath = layout.npmInstallPath(npm, resolvedScope);
                var needsInstall = !Files.exists(installedPath)
                    || !layout.installedNpmMatchesConfiguredVersion(npm, installedPath);
                if (needsInstall) {
                    if (!installMissing(onMissing, parsed, resolvedScope, resolvedSource)) {
                        continue;
                    }
                    installedPath = layout.npmInstallPath(npm, resolvedScope);
                }
                var packageMetadata = metadata.withBaseDir(installedPath.toString())
                    .withPackageRoot(installedPath.toString());
                collector.collectPackageResources(installedPath, accumulator, filter,
                    packageMetadata);
                continue;
            }

            var git = (GitSource) parsed;
            var gitInstalledPath = layout.gitInstallPath(git, resolvedScope);
            if (!Files.exists(gitInstalledPath)) {
                if (!installMissing(onMissing, parsed, resolvedScope, resolvedSource)) {
                    continue;
                }
            } else if (resolvedScope == SourceScope.TEMPORARY && !git.pinned()
                && !PackageEnvironment.isOfflineModeEnabled()) {
                gitStore.refreshTemporaryGitSource(git, resolvedSource);
            }
            var gitMetadata = metadata.withBaseDir(gitInstalledPath.toString())
                .withPackageRoot(gitInstalledPath.toString());
            collector.collectPackageResources(gitInstalledPath, accumulator, filter, gitMetadata);
        }
    }

    /** pi {@code installMissing} 闭包（:1302-1313）：离线 ⇒ 跳过；无回调 ⇒
     *  直装；回调 skip ⇒ 跳过、error ⇒ 抛（逐字）、install ⇒ 装。 */
    private boolean installMissing(MissingSourceHandler onMissing, ParsedSource parsed,
            SourceScope scope, String resolvedSource) {
        if (PackageEnvironment.isOfflineModeEnabled()) {
            return false;
        }
        if (onMissing == null) {
            installParsedSource(parsed, scope);
            return true;
        }
        var action = onMissing.onMissing(resolvedSource);
        if (action == MissingSourceAction.SKIP) {
            return false;
        }
        if (action == MissingSourceAction.ERROR) {
            // 模型可见文案，逐字。
            throw new IllegalStateException("Missing source: " + resolvedSource);
        }
        installParsedSource(parsed, scope);
        return true;
    }

    /** pi {@code installParsedSource}（:1393-1401）。 */
    private void installParsedSource(ParsedSource parsed, SourceScope scope) {
        try {
            if (parsed instanceof NpmSource npm) {
                var installRoot = layout.npmInstallRoot(scope, scope == SourceScope.TEMPORARY);
                layout.ensureNpmProject(installRoot);
                npmResolver.install(npm, installRoot);
                return;
            }
            if (parsed instanceof GitSource git) {
                gitStore.installGit(git, scope);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** pi {@code findAutoloadDeltaBase}（:1342-1357）：project autoload=false
     *  条目 ⇒ 资源从 user 同身份条目的安装收。 */
    private ScopedSource findAutoloadDeltaBase(PackageSource pkg, SourceScope scope,
            List<ScopedPackage> sources) {
        if (scope != SourceScope.PROJECT || pkg.stringForm()
            || !Boolean.FALSE.equals(pkg.autoload())) {
            return null;
        }
        var identity = resolver.getPackageIdentity(pkg.source(), scope);
        for (var entry : sources) {
            if (entry.scope() == SourceScope.USER
                && resolver.getPackageIdentity(
                    SourceParser.getPackageSourceString(entry.pkg()), SourceScope.USER)
                    .equals(identity)) {
                return new ScopedSource(SourceParser.getPackageSourceString(entry.pkg()),
                    SourceScope.USER);
            }
        }
        return null;
    }

    private record ScopedSource(String source, SourceScope scope) {}

    // ── 输出整形（:2637-2663）─────────────────────────────────────

    /** pi {@code toResolvedPaths}：每类按 rank 升序（稳定排序＝同 rank 插入序），
     *  再按 canonicalize 去重——同路径先到者胜。 */
    private static ResolvedPaths toResolvedPaths(ResourceAccumulator accumulator) {
        return new ResolvedPaths(
            mapToResolved(accumulator.target(ResourceType.EXTENSIONS)),
            mapToResolved(accumulator.target(ResourceType.SKILLS)),
            mapToResolved(accumulator.target(ResourceType.PROMPTS)),
            mapToResolved(accumulator.target(ResourceType.THEMES)));
    }

    private static List<ResolvedResource> mapToResolved(
            java.util.Map<String, Slot> entries) {
        var resolved = new ArrayList<ResolvedResource>();
        for (var entry : entries.entrySet()) {
            resolved.add(new ResolvedResource(entry.getKey(), entry.getValue().enabled(),
                entry.getValue().metadata()));
        }
        // Java sort 稳定 ≙ pi Array.sort 稳定（ES2019+）。
        resolved.sort(Comparator.comparingInt(
            r -> PathMetadata.resourcePrecedenceRank(r.metadata())));
        var seen = new HashSet<String>();
        var deduped = new ArrayList<ResolvedResource>();
        for (var entry : resolved) {
            if (seen.add(canonicalizePath(entry.path()))) {
                deduped.add(entry);
            }
        }
        return deduped;
    }

    /** pi {@code canonicalizePath}（utils/paths.ts:27-33）：realpath 失败回原串。
     *  java 对位 {@code toRealPath(NOFOLLOW_LINKS)}（docs/29 §3 T5 裁决文）。 */
    private static String canonicalizePath(String path) {
        try {
            return Path.of(path).toRealPath(LinkOption.NOFOLLOW_LINKS).toString();
        } catch (IOException | java.nio.file.InvalidPathException | SecurityException e) {
            return path;
        }
    }

    // ── 小件 ─────────────────────────────────────────────────────

    private void resolveLocalEntriesSafe(List<String> entries, ResourceType type,
            ResourceAccumulator accumulator, PathMetadata metadata, Path baseDir) {
        collector.resolveLocalEntries(entries, type, accumulator, metadata, baseDir);
    }

    private static List<String> orEmpty(List<String> entries) {
        return entries == null ? List.of() : entries;
    }

    private static List<String> settingsList(Settings settings, ResourceType type) {
        return switch (type) {
            case EXTENSIONS -> settings.extensions;
            case SKILLS -> settings.skills;
            case PROMPTS -> settings.prompts;
            case THEMES -> settings.themes;
        };
    }

    /** pi {@code withProgress}（:903-918）：操作前 start，成 complete，转发
     *  error（message＝异常 message）并把异常继续抛。 */
    void withProgress(Action action, String source, String message, IoRunnable operation)
            throws IOException {
        emitProgress(ProgressEvent.start(action, source, message));
        try {
            operation.run();
            emitProgress(ProgressEvent.complete(action, source));
        } catch (IOException | RuntimeException e) {
            emitProgress(ProgressEvent.error(action, source, e.getMessage()));
            throw e;
        }
    }

    private void emitProgress(ProgressEvent event) {
        var callback = progressCallback;
        if (callback != null) {
            callback.onProgress(event);
        }
    }

    @FunctionalInterface
    interface IoRunnable {
        void run() throws IOException;
    }
}
