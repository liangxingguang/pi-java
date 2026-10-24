package com.pijava.coding.agent.pkg;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * pi 目录扫描族（package-manager.ts:315-659）逐字对位——{@code collectFiles}/
 * {@code collectSkillEntries}（pi/agents 两 mode）/{@code collectAuto*Entries}/
 * {@code resolveExtensionEntries}/{@code collectResourceFiles}/
 * {@code collectFilesFromPaths}/{@code expandPackageGlob}/
 * {@code collectAncestorAgentsSkillDirs}。
 *
 * <p><b>java 翻译点（裁决⑤，窄差 D111）</b>：扩展文件型 {@code .ts/.js} ⇒
 * {@code .jar}；{@code resolveExtensionEntries} 的 index 回落翻译为
 * {@code index.jar}；manifest 的 extensions 条目原样尊重。符号链接：
 * {@code Files.isDirectory/isRegularFile} 默认跟链接（≙ pi statSync）；断裂
 * 链接/读不动 ⇒ 跳过（pi statSync catch continue 对位）。</p>
 */
public final class PackageResourceScanner {

    /** pi {@code SkillDiscoveryMode}。 */
    public enum SkillMode { PI, AGENTS }

    private PackageResourceScanner() {}

    /** {@code Path.getFileName()} 空安全（根路径无文件名；spotbugs NP 闸）。 */
    private static String entryName(Path entry) {
        var name = entry.getFileName();
        return name == null ? "" : name.toString();
    }

    /** pi {@code FILE_PATTERNS}（:220-226；extensions 翻译为 .jar，裁决⑤）。 */
    public static Predicate<String> fileNamePattern(ResourceType type) {
        return switch (type) {
            case EXTENSIONS -> name -> name.endsWith(".jar");
            case SKILLS, PROMPTS -> name -> name.endsWith(".md");
            case THEMES -> name -> name.endsWith(".json");
        };
    }

    // ── collectFiles（:315-365）──────────────────────────────────

    /** pi {@code collectFiles}：递归扫，跳 dot 开头条目与 node_modules；目录查
     *  忽略；全函数 try/catch 静默。 */
    public static List<String> collectFiles(Path dir, ResourceType type) {
        return collectFiles(dir, fileNamePattern(type), true, new PackageIgnore(), dir);
    }

    static List<String> collectFiles(Path dir, Predicate<String> namePattern,
            boolean skipNodeModules, PackageIgnore ignore, Path root) {
        var files = new ArrayList<String>();
        if (!Files.exists(dir)) {
            return files;
        }
        ignore.addRules(dir, root);
        try (var stream = Files.list(dir)) {
            for (var entry : (Iterable<Path>) stream::iterator) {
                var name = entryName(entry);
                if (name.startsWith(".")) {
                    continue;
                }
                if (skipNodeModules && name.equals("node_modules")) {
                    continue;
                }
                var isDir = Files.isDirectory(entry);
                var isFile = Files.isRegularFile(entry);
                if (!isDir && !isFile) {
                    continue; // 断裂符号链接等（pi statSync catch 臂）
                }
                var relPath = ResourcePatterns.toPosixPath(root.relativize(entry).toString());
                if (ignore.ignores(relPath, isDir)) {
                    continue;
                }
                if (isDir) {
                    files.addAll(collectFiles(entry, namePattern, skipNodeModules, ignore, root));
                } else if (namePattern.test(name)) {
                    files.add(entry.toString());
                }
            }
        } catch (IOException e) {
            // Ignore errors
        }
        return files;
    }

    // ── collectSkillEntries（:373-448）────────────────────────────

    public static List<String> collectSkillEntries(Path dir, SkillMode mode) {
        return collectSkillEntries(dir, mode, new PackageIgnore(), dir);
    }

    /** pi 逐字：含 SKILL.md 的目录即技能根、不再下钻；pi 模式只收 root 直属
     *  .md，agents 模式只收子目录里的 .md。 */
    static List<String> collectSkillEntries(Path dir, SkillMode mode, PackageIgnore ignore, Path root) {
        var entries = new ArrayList<String>();
        if (!Files.exists(dir)) {
            return entries;
        }
        ignore.addRules(dir, root);

        List<Path> dirEntries;
        try (var stream = Files.list(dir)) {
            dirEntries = stream.toList();
        } catch (IOException e) {
            return entries;
        }

        for (var entry : dirEntries) {
            if (!entryName(entry).equals("SKILL.md")) {
                continue;
            }
            if (!Files.isRegularFile(entry)) {
                continue;
            }
            var relPath = ResourcePatterns.toPosixPath(root.relativize(entry).toString());
            if (!ignore.ignores(relPath, false)) {
                entries.add(entry.toString());
                return entries;
            }
        }

        for (var entry : dirEntries) {
            var name = entryName(entry);
            if (name.startsWith(".") || name.equals("node_modules")) {
                continue;
            }
            var isDir = Files.isDirectory(entry);
            var isFile = Files.isRegularFile(entry);
            if (!isDir && !isFile) {
                continue;
            }
            var relPath = ResourcePatterns.toPosixPath(root.relativize(entry).toString());
            var isMarkdown = isFile && name.endsWith(".md") && !ignore.ignores(relPath, false)
                && ((mode == SkillMode.PI && dir.equals(root))
                    || (mode == SkillMode.AGENTS && !dir.equals(root)));
            if (isMarkdown) {
                entries.add(entry.toString());
                continue;
            }
            if (!isDir || ignore.ignores(relPath, true)) {
                continue;
            }
            entries.addAll(collectSkillEntries(entry, mode, ignore, root));
        }
        return entries;
    }

    // ── 祖先 .agents/skills（:450-484）────────────────────────────

    /** pi {@code findGitRepoRoot}。 */
    public static Path findGitRepoRoot(Path startDir) {
        var dir = startDir.toAbsolutePath().normalize();
        while (true) {
            if (Files.exists(dir.resolve(".git"))) {
                return dir;
            }
            var parent = dir.getParent();
            if (parent == null || parent.equals(dir)) {
                return null;
            }
            dir = parent;
        }
    }

    /** pi {@code collectAncestorAgentsSkillDirs}：cwd 向上逐层
     *  {@code .agents/skills}，git 根截断。 */
    public static List<Path> collectAncestorAgentsSkillDirs(Path startDir) {
        var skillDirs = new ArrayList<Path>();
        var resolved = startDir.toAbsolutePath().normalize();
        var gitRepoRoot = findGitRepoRoot(resolved);

        var dir = resolved;
        while (true) {
            skillDirs.add(dir.resolve(".agents").resolve("skills"));
            if (gitRepoRoot != null && dir.equals(gitRepoRoot)) {
                break;
            }
            var parent = dir.getParent();
            if (parent == null || parent.equals(dir)) {
                break;
            }
            dir = parent;
        }
        return skillDirs;
    }

    // ── prompts/themes 平铺（:487-561）────────────────────────────

    public static List<String> collectAutoPromptEntries(Path dir) {
        return collectAutoFlatEntries(dir, name -> name.endsWith(".md"));
    }

    public static List<String> collectAutoThemeEntries(Path dir) {
        return collectAutoFlatEntries(dir, name -> name.endsWith(".json"));
    }

    /** pi {@code collectAutoPromptEntries}/{@code collectAutoThemeEntries}
     *  （:487-561）：直属文件平铺（不递归）。 */
    private static List<String> collectAutoFlatEntries(Path dir, Predicate<String> namePattern) {
        var entries = new ArrayList<String>();
        if (!Files.exists(dir)) {
            return entries;
        }
        var ignore = new PackageIgnore();
        ignore.addRules(dir, dir);
        try (var stream = Files.list(dir)) {
            for (var entry : (Iterable<Path>) stream::iterator) {
                var name = entryName(entry);
                if (name.startsWith(".") || name.equals("node_modules")) {
                    continue;
                }
                if (!Files.isRegularFile(entry)) {
                    continue;
                }
                var relPath = ResourcePatterns.toPosixPath(dir.relativize(entry).toString());
                if (ignore.ignores(relPath, false)) {
                    continue;
                }
                if (namePattern.test(name)) {
                    entries.add(entry.toString());
                }
            }
        } catch (IOException e) {
            // Ignore errors
        }
        return entries;
    }

    // ── 扩展发现（:563-645；.jar 翻译，裁决⑤）─────────────────────

    /** pi {@code resolveExtensionEntries}：manifest extensions 条目（存在即收）
     *  ⇒ {@code index.jar} 回落；皆无 ⇒ null。 */
    public static List<String> resolveExtensionEntries(Path dir) {
        var packageJsonPath = dir.resolve("package.json");
        if (Files.exists(packageJsonPath)) {
            var manifest = PiManifest.read(packageJsonPath);
            if (manifest != null && manifest.extensions() != null
                && !manifest.extensions().isEmpty()) {
                var entries = new ArrayList<String>();
                for (var extPath : manifest.extensions()) {
                    var resolved = dir.resolve(extPath).normalize();
                    if (Files.exists(resolved)) {
                        entries.add(resolved.toString());
                    }
                }
                if (!entries.isEmpty()) {
                    return entries;
                }
            }
        }
        var indexJar = dir.resolve("index.jar");
        if (Files.exists(indexJar)) {
            return List.of(indexJar.toString());
        }
        return null;
    }

    /** pi {@code collectAutoExtensionEntries}：目录自身有显式入口就用它；否则
     *  扫直属 .jar＋子目录显式入口。 */
    public static List<String> collectAutoExtensionEntries(Path dir) {
        var entries = new ArrayList<String>();
        if (!Files.exists(dir)) {
            return entries;
        }
        var rootEntries = resolveExtensionEntries(dir);
        if (rootEntries != null) {
            return rootEntries;
        }
        var ignore = new PackageIgnore();
        ignore.addRules(dir, dir);
        try (var stream = Files.list(dir)) {
            for (var entry : (Iterable<Path>) stream::iterator) {
                var name = entryName(entry);
                if (name.startsWith(".") || name.equals("node_modules")) {
                    continue;
                }
                var isDir = Files.isDirectory(entry);
                var isFile = Files.isRegularFile(entry);
                if (!isDir && !isFile) {
                    continue;
                }
                var relPath = ResourcePatterns.toPosixPath(dir.relativize(entry).toString());
                if (ignore.ignores(relPath, isDir)) {
                    continue;
                }
                if (isFile && name.endsWith(".jar")) {
                    entries.add(entry.toString());
                } else if (isDir) {
                    var resolvedEntries = resolveExtensionEntries(entry);
                    if (resolvedEntries != null) {
                        entries.addAll(resolvedEntries);
                    }
                }
            }
        } catch (IOException e) {
            // Ignore errors
        }
        return entries;
    }

    // ── 汇总入口（:647-659）──────────────────────────────────────

    /** pi {@code collectResourceFiles}：skills/extensions 走智能发现，其他递归。 */
    public static List<String> collectResourceFiles(Path dir, ResourceType type) {
        if (type == ResourceType.SKILLS) {
            return collectSkillEntries(dir, SkillMode.PI);
        }
        if (type == ResourceType.EXTENSIONS) {
            return collectAutoExtensionEntries(dir);
        }
        return collectFiles(dir, type);
    }

    /** pi {@code collectFilesFromPaths}（:2581-2599）：文件直收，目录按型收。 */
    public static List<String> collectFilesFromPaths(List<String> paths, ResourceType type) {
        var files = new ArrayList<String>();
        for (var p : paths) {
            var path = Path.of(p);
            if (!Files.exists(path)) {
                continue;
            }
            if (Files.isRegularFile(path)) {
                files.add(p);
            } else if (Files.isDirectory(path)) {
                files.addAll(collectResourceFiles(path, type));
            }
        }
        return files;
    }

    /** pi {@code expandPackageGlob}（:296-300）：glob 展开可见路径（dot 段不收），
     *  字典序。 */
    public static List<String> expandPackageGlob(String pattern, Path root) {
        var matches = new ArrayList<String>();
        if (!Files.exists(root)) {
            return matches;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> !p.equals(root))
                .filter(p -> {
                    // dot:false：任何 . 开头段不进匹配（pi 后过滤对位前置）
                    for (var seg : root.relativize(p)) {
                        if (seg.toString().startsWith(".")) {
                            return false;
                        }
                    }
                    return true;
                })
                .filter(p -> MinimatchLite.match(pattern,
                    ResourcePatterns.toPosixPath(root.relativize(p).toString())))
                .forEach(p -> matches.add(p.toAbsolutePath().normalize().toString()));
        } catch (IOException e) {
            // Ignore errors
        }
        matches.sort(String::compareTo);
        return matches;
    }
}
