package com.pijava.coding.agent.theme;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import com.pijava.coding.agent.skill.ResourceDiagnostic;

/**
 * 主题发现与装载（pi {@code resource-loader.ts} 的 {@code loadThemes}/
 * {@code loadThemesFromDir}/{@code loadThemeFromFile}/{@code dedupeThemes}
 * :1043-1112/:1020-1041 对位，批 21/docs/32 T8）。
 *
 * <p>默认目录（{@code <agentDir>/themes}、项目 {@code .pi-java/themes}）不在此
 * 扫描——与 pi 一样由包 resolve 的自动发现条目带进来（java 批 18
 * {@code PackageResourceScanner.collectAutoThemeEntries} 已对齐）。</p>
 */
public final class ThemeLoader {

    private ThemeLoader() {}

    /** 装载结果：主题表＋诊断表（pi {@code {themes, diagnostics}} 对位）。 */
    public record ThemeLoadResult(List<Theme> themes, List<ResourceDiagnostic> diagnostics) {}

    /**
     * pi {@code loadThemes}（:1043-1088，includeDefaults=false 臂——默认目录由
     * resolve 自动发现供给）＋{@code dedupeThemes}。
     *
     * @param paths 主题路径（文件或目录，字符串形——resolve 的平铺结果）
     * @param mode 主题构造的色彩档
     */
    public static ThemeLoadResult loadThemes(List<String> paths, TerminalColorMode mode) {
        var themes = new ArrayList<Theme>();
        var diagnostics = new ArrayList<ResourceDiagnostic>();
        for (var p : paths) {
            var resolved = Path.of(p);
            if (!Files.exists(resolved)) {
                diagnostics.add(ResourceDiagnostic.warning(p, "theme path does not exist"));
                continue;
            }
            try {
                if (Files.isDirectory(resolved)) {
                    loadThemesFromDir(resolved, themes, diagnostics, mode);
                } else if (Files.isRegularFile(resolved) && p.endsWith(".json")) {
                    loadThemeFromFile(resolved, themes, diagnostics, mode);
                } else {
                    diagnostics.add(
                        ResourceDiagnostic.warning(p, "theme path is not a json file"));
                }
            } catch (RuntimeException e) {
                var message = e.getMessage() != null ? e.getMessage() : "failed to read theme path";
                diagnostics.add(ResourceDiagnostic.warning(p, message));
            }
        }
        var deduped = dedupeThemes(themes);
        diagnostics.addAll(deduped.diagnostics());
        return new ThemeLoadResult(deduped.themes(), diagnostics);
    }

    /**
     * pi {@code loadThemesFromDir}（:1066-1094）：目录不在 ⇒ 静默；只收
     * {@code *.json} 常规文件（符号链接跟随，破链跳过）。
     */
    private static void loadThemesFromDir(Path dir, List<Theme> themes,
                                          List<ResourceDiagnostic> diagnostics,
                                          TerminalColorMode mode) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var stream = Files.list(dir)) {
            for (var entry : stream.toList()) {
                // Files.isRegularFile 默认跟随符号链接（pi statSync 同）；破链 ⇒ false
                if (!Files.isRegularFile(entry)) {
                    continue;
                }
                var fileName = entry.getFileName();
                if (fileName == null || !fileName.toString().endsWith(".json")) {
                    continue;
                }
                loadThemeFromFile(entry, themes, diagnostics, mode);
            }
        } catch (IOException | RuntimeException e) {
            var message = e.getMessage() != null ? e.getMessage() : "failed to read theme directory";
            diagnostics.add(ResourceDiagnostic.warning(dir.toString(), message));
        }
    }

    /** pi {@code loadThemeFromFile}（:1101-1112）：失败 ⇒ warning 诊断不中断。 */
    private static void loadThemeFromFile(Path filePath, List<Theme> themes,
                                          List<ResourceDiagnostic> diagnostics,
                                          TerminalColorMode mode) {
        try {
            themes.add(Themes.loadThemeFromPath(filePath, mode));
        } catch (RuntimeException e) {
            var message = e.getMessage() != null ? e.getMessage() : "failed to load theme";
            diagnostics.add(ResourceDiagnostic.warning(filePath.toString(), message));
        }
    }

    /**
     * pi {@code dedupeThemes}（:1020-1041 逐字）：同名（缺名 ⇒ {@code "unnamed"}）
     先到先得；后到者落 {@code collision} 诊断（{@code name "<name>" collision}，
     缺源路径记 {@code <builtin>}）。
     */
    static ThemeLoadResult dedupeThemes(List<Theme> themes) {
        var seen = new LinkedHashMap<String, Theme>();
        var diagnostics = new ArrayList<ResourceDiagnostic>();
        for (var theme : themes) {
            var name = theme.name() != null ? theme.name() : "unnamed";
            var existing = seen.get(name);
            if (existing != null) {
                diagnostics.add(ResourceDiagnostic.collision(theme.sourcePath(),
                    "name \"" + name + "\" collision",
                    new ResourceDiagnostic.Collision("theme", name,
                        existing.sourcePath() != null ? existing.sourcePath() : "<builtin>",
                        theme.sourcePath() != null ? theme.sourcePath() : "<builtin>")));
            } else {
                seen.put(name, theme);
            }
        }
        return new ThemeLoadResult(List.copyOf(seen.values()), diagnostics);
    }
}
