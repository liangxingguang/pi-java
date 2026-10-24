package com.pijava.coding.agent.pkg;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.pijava.coding.agent.pkg.ParsedSource.LocalSource;

/**
 * pi 包资源收集族（package-manager.ts:2214-2420/:2358-2400/:1360-1390）
 * 逐字对位——{@code collectPackageResources} 三支（delta/pattern/default）＋
 * manifest 优先/约定目录回落＋{@code resolveLocalEntries}＋
 * {@code resolveLocalExtensionSource}。
 */
public final class PackageResourceCollector {

    private final SourceResolver resolver;

    public PackageResourceCollector(SourceResolver resolver) {
        this.resolver = resolver;
    }

    /** pi {@code collectPackageResources}（:2214-2263）。返回 false ⇒ local 源
     *  调用方把目录本身当扩展路径收。 */
    public boolean collectPackageResources(Path packageRoot, ResourceAccumulator accumulator,
            PackageFilter filter, PathMetadata metadata) {
        if (filter != null) {
            for (var resourceType : ResourceType.ALL) {
                var patterns = filter.patternsOf(resourceType);
                if (Boolean.FALSE.equals(filter.autoload())) {
                    applyPackageDeltaFilter(packageRoot,
                        patterns == null ? List.of() : patterns, resourceType, accumulator, metadata);
                } else if (patterns != null) {
                    applyPackageFilter(packageRoot, patterns, resourceType, accumulator, metadata);
                } else {
                    collectDefaultResources(packageRoot, resourceType, accumulator, metadata);
                }
            }
            return true;
        }

        var manifest = PiManifest.read(packageRoot.resolve("package.json"));
        if (manifest != null) {
            for (var resourceType : ResourceType.ALL) {
                addManifestEntries(manifest.entriesOf(resourceType), packageRoot,
                    resourceType, accumulator, metadata);
            }
            return true;
        }

        var hasAnyDir = false;
        for (var resourceType : ResourceType.ALL) {
            var dir = packageRoot.resolve(resourceType.id());
            if (Files.exists(dir)) {
                // Collect all files from the directory (all enabled by default)
                var files = PackageResourceScanner.collectResourceFiles(dir, resourceType);
                for (var f : files) {
                    accumulator.addResource(resourceType, f, metadata, true);
                }
                hasAnyDir = true;
            }
        }
        return hasAnyDir;
    }

    /** pi {@code collectDefaultResources}（:2265-2284）。 */
    private void collectDefaultResources(Path packageRoot, ResourceType resourceType,
            ResourceAccumulator accumulator, PathMetadata metadata) {
        var manifest = PiManifest.read(packageRoot.resolve("package.json"));
        var entries = manifest == null ? null : manifest.entriesOf(resourceType);
        if (entries != null) {
            // pi：entries 存在（含空数组）即走 manifest 臂，不再看约定目录。
            addManifestEntries(entries, packageRoot, resourceType, accumulator, metadata);
            return;
        }
        var dir = packageRoot.resolve(resourceType.id());
        if (Files.exists(dir)) {
            var files = PackageResourceScanner.collectResourceFiles(dir, resourceType);
            for (var f : files) {
                accumulator.addResource(resourceType, f, metadata, true);
            }
        }
    }

    /** pi {@code applyPackageFilter}（:2286-2309）：空数组显式全停该型。 */
    private void applyPackageFilter(Path packageRoot, List<String> userPatterns,
            ResourceType resourceType, ResourceAccumulator accumulator, PathMetadata metadata) {
        var allFiles = collectManifestFiles(packageRoot, resourceType).allFiles();
        if (userPatterns.isEmpty()) {
            // Empty array explicitly disables all resources of this type
            for (var f : allFiles) {
                accumulator.addResource(resourceType, f, metadata, false);
            }
            return;
        }
        var enabledByUser = ResourcePatterns.applyPatterns(allFiles, userPatterns,
            packageRoot.toString());
        for (var f : allFiles) {
            accumulator.addResource(resourceType, f, metadata, enabledByUser.contains(f));
        }
    }

    /** pi {@code applyPackageDeltaFilter}（:2313-2329）：autoload=false 的 delta
     *  语义——只写映射里点名的条目。 */
    private void applyPackageDeltaFilter(Path packageRoot, List<String> userPatterns,
            ResourceType resourceType, ResourceAccumulator accumulator, PathMetadata metadata) {
        if (userPatterns.isEmpty()) {
            return;
        }
        var allFiles = collectManifestFiles(packageRoot, resourceType).allFiles();
        var enabledByUser = ResourcePatterns.applyAutoloadDisabledPatterns(
            allFiles, userPatterns, packageRoot.toString());
        for (var entry : enabledByUser.entrySet()) {
            accumulator.addResource(resourceType, entry.getKey(), metadata, entry.getValue());
        }
    }

    /** pi {@code collectManifestFiles}（:2336-2356）：manifest 条目收集＋条目内
     *  override pattern 过滤；无 manifest ⇒ 约定目录全收。 */
    ManifestFiles collectManifestFiles(Path packageRoot, ResourceType resourceType) {
        var manifest = PiManifest.read(packageRoot.resolve("package.json"));
        var entries = manifest == null ? null : manifest.entriesOf(resourceType);
        if (entries != null && !entries.isEmpty()) {
            var allFiles = collectFilesFromManifestEntries(entries, packageRoot, resourceType);
            var manifestPatterns = entries.stream()
                .filter(ResourcePatterns::isOverridePattern).toList();
            Set<String> enabledByManifest = !manifestPatterns.isEmpty()
                ? ResourcePatterns.applyPatterns(allFiles, manifestPatterns, packageRoot.toString())
                : new LinkedHashSet<>(allFiles);
            return new ManifestFiles(new ArrayList<>(enabledByManifest), enabledByManifest);
        }
        var conventionDir = packageRoot.resolve(resourceType.id());
        if (!Files.exists(conventionDir)) {
            return new ManifestFiles(List.of(), Set.of());
        }
        var allFiles = PackageResourceScanner.collectResourceFiles(conventionDir, resourceType);
        return new ManifestFiles(allFiles, new LinkedHashSet<>(allFiles));
    }

    record ManifestFiles(List<String> allFiles, Set<String> enabledByManifest) {}

    /** pi {@code addManifestEntries}（:2358-2376）：条目 override pattern 过滤后
     *  只收 enabled。 */
    void addManifestEntries(List<String> entries, Path root, ResourceType resourceType,
            ResourceAccumulator accumulator, PathMetadata metadata) {
        if (entries == null) {
            return;
        }
        var allFiles = collectFilesFromManifestEntries(entries, root, resourceType);
        var patterns = entries.stream().filter(ResourcePatterns::isOverridePattern).toList();
        var enabledPaths = ResourcePatterns.applyPatterns(allFiles, patterns, root.toString());
        for (var f : allFiles) {
            if (enabledPaths.contains(f)) {
                accumulator.addResource(resourceType, f, metadata, true);
            }
        }
    }

    /** pi {@code collectFilesFromManifestEntries}（:2378-2389）。 */
    private List<String> collectFilesFromManifestEntries(List<String> entries, Path root,
            ResourceType resourceType) {
        var resolved = new ArrayList<String>();
        for (var entry : entries) {
            if (ResourcePatterns.isOverridePattern(entry)) {
                continue;
            }
            if (!ResourcePatterns.hasGlobPattern(entry)) {
                resolved.add(root.resolve(entry).normalize().toString());
            } else {
                resolved.addAll(PackageResourceScanner.expandPackageGlob(entry, root));
            }
        }
        return PackageResourceScanner.collectFilesFromPaths(resolved, resourceType);
    }

    /** pi {@code resolveLocalEntries}（:2391-2411）：settings 四类数组条目——
     *  plain 直收＋pattern 决 enabled。 */
    public void resolveLocalEntries(List<String> entries, ResourceType resourceType,
            ResourceAccumulator accumulator, PathMetadata metadata, Path baseDir) {
        if (entries.isEmpty()) {
            return;
        }
        var split = ResourcePatterns.splitPatterns(entries);
        var resolvedPlain = split.plain().stream()
            .map(p -> resolver.resolvePathFromBase(p, baseDir))
            .toList();
        var allFiles = PackageResourceScanner.collectFilesFromPaths(resolvedPlain, resourceType);
        var enabledPaths = ResourcePatterns.applyPatterns(allFiles, split.patterns(),
            baseDir.toString());
        for (var f : allFiles) {
            accumulator.addResource(resourceType, f, metadata, enabledPaths.contains(f));
        }
    }

    /** pi {@code resolveLocalExtensionSource}（:1360-1390）：文件直收；目录走
     *  包资源收集，四类皆空回 false ⇒ 目录本身当扩展路径收。 */
    public void resolveLocalExtensionSource(LocalSource source, ResourceAccumulator accumulator,
            PackageFilter filter, PathMetadata metadata, Path baseDir) {
        var resolved = Path.of(resolver.resolvePathFromBase(source.path(), baseDir));
        if (!Files.exists(resolved)) {
            return;
        }
        try {
            if (Files.isRegularFile(resolved)) {
                var parent = resolved.getParent();
                accumulator.addResource(ResourceType.EXTENSIONS, resolved.toString(),
                    metadata.withBaseDir(parent != null ? parent.toString()
                        : resolved.toString()), true);
                return;
            }
            if (Files.isDirectory(resolved)) {
                var dirMetadata = metadata.withBaseDir(resolved.toString())
                    .withPackageRoot(resolved.toString());
                var resources = collectPackageResources(resolved, accumulator, filter, dirMetadata);
                if (!resources) {
                    accumulator.addResource(ResourceType.EXTENSIONS, resolved.toString(),
                        dirMetadata, true);
                }
            }
        } catch (RuntimeException e) {
            // pi catch { return; } 对位
        }
    }
}
