package com.pijava.coding.agent.skill;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.pijava.agent.skill.Skill;
import com.pijava.agent.skill.SkillSource;

/**
 * 技能目录递归扫描 + 多来源合并去重（对齐 pi {@code loadSkillsFromDirInternal}）。
 *
 * <p>目录三规则：① 含 {@code SKILL.md} 的目录视为技能根，不再向下递归；② 否则加载
 * 根目录下的直接 {@code .md} 子文件（仅当 {@code includeRootFiles}）；③ 递归子目录
 * 寻找 {@code SKILL.md}（递归时 {@code includeRootFiles=false}）。忽略过滤遵循
 * {@code .gitignore/.ignore/.fdignore}（JGit IgnoreNode）。</p>
 */
public final class SkillDiscovery {

    private final Path cwd;
    private final Path agentDir;
    private final MarkdownSkillLoader loader = new MarkdownSkillLoader();

    /**
     * @param cwd      项目工作目录（PROJECT 技能目录按此定位）
     * @param agentDir 全局 agent 配置根（USER 技能目录按此定位）
     */
    public SkillDiscovery(Path cwd, Path agentDir) {
        this.cwd = cwd;
        this.agentDir = agentDir;
    }

    /** 扫描全部来源并合并去重（USER → PROJECT → EXPLICIT，后加载覆盖先加载）。 */
    public LoadSkillsResult discoverAll(boolean includeDefaults, List<Path> explicitPaths) {
        var results = new ArrayList<LoadSkillsResult>();
        if (includeDefaults) {
            results.add(loadDirectory(
                agentDir.resolve("skills"), SkillSource.USER, true));
            results.add(loadDirectory(
                cwd.resolve(".pi-java").resolve("skills"), SkillSource.PROJECT, true));
        }
        for (var path : explicitPaths) {
            results.add(loadExplicit(path));
        }
        return LoadSkillsResult.dedupe(LoadSkillsResult.merge(results));
    }

    /** 扫描单个目录。 */
    public LoadSkillsResult loadDirectory(Path dir, SkillSource source, boolean includeRootFiles) {
        if (!Files.isDirectory(dir)) {
            return LoadSkillsResult.empty();
        }
        var skills = new ArrayList<Skill>();
        var diagnostics = new ArrayList<ResourceDiagnostic>();
        var filter = new IgnoreFilter(dir);
        scan(dir, dir, source, includeRootFiles, filter, skills, diagnostics);
        return new LoadSkillsResult(List.copyOf(skills), List.copyOf(diagnostics));
    }

    // ── 递归扫描 ─────────────────────────────────────────────────────────

    private void scan(Path dir, Path root, SkillSource source, boolean includeRootFiles,
                      IgnoreFilter filter, List<Skill> skills,
                      List<ResourceDiagnostic> diagnostics) {
        filter.push(dir);
        try {
            Path skillMd = dir.resolve("SKILL.md");
            String skillMdRel = IgnoreFilter.toPosix(root.relativize(skillMd));
            if (Files.isRegularFile(skillMd) && !filter.isIgnored(skillMdRel, false)) {
                var r = loader.loadFile(skillMd, source);
                r.skill().ifPresent(skills::add);
                diagnostics.addAll(r.diagnostics());
                return; // 规则①：不再向下递归
            }
            try (var entries = Files.list(dir)) {
                for (var file : entries.sorted().toList()) {
                    String rel = IgnoreFilter.toPosix(root.relativize(file));
                    if (Files.isDirectory(file)) {
                        if (!filter.isIgnored(rel, true)) {
                            // 规则③：递归子目录（includeRootFiles=false）
                            scan(file, root, source, false, filter, skills, diagnostics);
                        }
                    } else if (includeRootFiles && rel.endsWith(".md")) {
                        if (!filter.isIgnored(rel, false)) {
                            var r = loader.loadFile(file, source);
                            r.skill().ifPresent(skills::add);
                            diagnostics.addAll(r.diagnostics());
                        }
                    }
                }
            }
        } catch (IOException e) {
            diagnostics.add(ResourceDiagnostic.error(dir.toString(),
                "Cannot scan directory: " + e.getMessage()));
        } finally {
            filter.pop();
        }
    }

    private LoadSkillsResult loadExplicit(Path path) {
        if (Files.isDirectory(path)) {
            return loadDirectory(path, SkillSource.EXPLICIT, true);
        }
        if (Files.isRegularFile(path)) {
            var r = loader.loadFile(path, SkillSource.EXPLICIT);
            return new LoadSkillsResult(
                r.skill().map(List::of).orElseGet(List::of), r.diagnostics());
        }
        return new LoadSkillsResult(List.of(), List.of(
            ResourceDiagnostic.error(path.toString(), "Not a file or directory")));
    }
}
