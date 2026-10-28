package com.pijava.coding.agent.theme;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import com.pijava.coding.agent.core.FileSettingsStorage;
import com.pijava.coding.agent.core.Json;

/**
 * 主题装载面（结构债拆分：{@link Themes} 的「主题加载（pi :379-566）」与
 * 「可选主题列表（pi :394-459）」两节整块迁出，行为零变化）——内建/自定义/
 * 注册表三源的读取、解析与列举。注册表读面经 {@link Themes#registeredThemesSnapshot()}
 * 快照（{@code REGISTERED_THEMES} 仍由 {@code Themes} 类锁独占持有）。
 */
public final class ThemeCatalog {

    private ThemeCatalog() {}

    // ============================================================================
    // 主题加载（pi :379-566）
    // ============================================================================

    private static Map<String, ThemeJson> builtinThemes;

    private static synchronized Map<String, ThemeJson> builtinThemes() {
        if (builtinThemes == null) {
            builtinThemes = new LinkedHashMap<>();
            builtinThemes.put("dark", readBuiltin("dark"));
            builtinThemes.put("light", readBuiltin("light"));
        }
        return builtinThemes;
    }

    private static ThemeJson readBuiltin(String name) {
        try (var stream = Themes.class.getResourceAsStream("/themes/" + name + ".json")) {
            if (stream == null) {
                throw new IllegalStateException("Built-in theme resource missing: " + name);
            }
            return ThemeJsonValidator.validate(name,
                Json.mapper().readTree(stripBom(new String(stream.readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8))));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load built-in theme " + name, e);
        }
    }

    /** pi {@code stripBom}（utils/text.ts）——UTF-8 BOM 剥离（本包与 core 共用）。 */
    public static String stripBom(String content) {
        return content.startsWith("﻿") ? content.substring(1) : content;
    }

    /** 自定义主题目录（pi {@code getCustomThemesDir}＝{@code <agentDir>/themes}）。 */
    static Path customThemesDir() {
        return FileSettingsStorage.defaultAgentDir().resolve("themes");
    }

    /** pi {@code parseThemeJsonContent}（:477-485）＋恒装校验器（裁决②）。 */
    static ThemeJson parseThemeJsonContent(String label, String content) {
        JsonNode json;
        try {
            json = Json.mapper().readTree(stripBom(content));
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("Failed to parse theme " + label + ": " + e);
        }
        return ThemeJsonValidator.validate(label, json);
    }

    /** pi {@code loadThemeJson}（:487-507 逐字序）。 */
    static ThemeJson loadThemeJson(String name) {
        var builtins = builtinThemes();
        if (builtins.containsKey(name)) {
            return builtins.get(name);
        }
        Theme registeredTheme = Themes.registeredThemesSnapshot().get(name);
        if (registeredTheme != null && registeredTheme.sourcePath() != null) {
            return parseThemeJsonContent(registeredTheme.sourcePath(),
                readString(Path.of(registeredTheme.sourcePath())));
        }
        if (registeredTheme != null) {
            throw new IllegalArgumentException(
                "Theme \"" + name + "\" does not have a source path for export");
        }
        var themePath = customThemesDir().resolve(name + ".json");
        if (!Files.exists(themePath)) {
            throw new IllegalArgumentException("Theme not found: " + name);
        }
        return parseThemeJsonContent(name, readString(themePath));
    }

    private static String readString(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** pi {@code loadThemeFromPath}（:551-555）。 */
    public static Theme loadThemeFromPath(Path themePath) {
        return loadThemeFromPath(themePath, Themes.colorMode());
    }

    /** 带色彩档的 {@link #loadThemeFromPath(Path)}。 */
    public static Theme loadThemeFromPath(Path themePath, TerminalColorMode mode) {
        var themeJson = parseThemeJsonContent(themePath.toString(), readString(themePath));
        return Theme.createTheme(themeJson, mode, themePath.toString());
    }

    /** pi {@code createSystemTheme}（:537-549）：由终端上报色生成（java 恒 tier-3）。 */
    private static Theme createSystemTheme(TerminalColorMode mode) {
        var terminal = Themes.terminalColors();
        var generated = SystemTheme.generateSystemThemeColors(
            new SystemTheme.SystemThemeInput(terminal.foreground(), terminal.background(),
                terminal.palette(), 1.0, Themes.terminalTheme()));
        return new Theme(Theme.withTokenFallbacks(new LinkedHashMap<>(generated.colors())),
            mode, new Theme.Options(SystemTheme.SYSTEM_THEME_NAME, null, generated.appearance(),
                generated.dim().isEmpty() ? null : java.util.Set.copyOf(generated.dim())));
    }

    /** pi {@code loadTheme}（:557-566）：system 保留名优先 → 注册表直返 → 文件。 */
    static Theme loadTheme(String name) {
        if (SystemTheme.SYSTEM_THEME_NAME.equals(name)) {
            return createSystemTheme(Themes.colorMode());
        }
        var registered = Themes.registeredThemesSnapshot().get(name);
        if (registered != null) {
            return registered;
        }
        return Theme.createTheme(loadThemeJson(name), Themes.colorMode(), null);
    }

    /** pi {@code getThemeByName}（:568-574）：加载失败 ⇒ null。 */
    public static Theme getThemeByName(String name) {
        try {
            return loadTheme(name);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ============================================================================
    // 可选主题列表（pi :394-459）
    // ============================================================================

    /** pi {@code getAvailableThemes}：名字列表（system 置顶，其余按名字排序）。 */
    public static List<String> getAvailableThemes() {
        return getAvailableThemesWithPaths().stream().map(Themes.ThemeInfo::name).toList();
    }

    /**
     * pi {@code getAvailableThemesWithPaths}（:403-434 逐字）：system（无文件）→
     * 内建 → 自定义目录 → 注册表；去重先到先得；system 置顶其余
     * {@code localeCompare}。
     */
    public static List<Themes.ThemeInfo> getAvailableThemesWithPaths() {
        var result = new ArrayList<Themes.ThemeInfo>();
        var seen = new java.util.LinkedHashSet<String>();
        java.util.function.Consumer<Themes.ThemeInfo> addTheme = info -> {
            if (seen.add(info.name())) {
                result.add(info);
            }
        };

        // Built-in themes. The system theme is generated, so it has no file.
        addTheme.accept(new Themes.ThemeInfo(SystemTheme.SYSTEM_THEME_NAME, null));
        // 内建主题在 java 是 classpath 资源（无 FS 路径）⇒ path=null（窄差注记：
        // pi 给包目录里的文件路径； ThemeInfo.path 的消费面是展示）。
        for (var name : builtinThemes().keySet()) {
            addTheme.accept(new Themes.ThemeInfo(name, null));
        }

        // Custom themes（pi getCustomThemeInfos :436-459：非法主题此处忽略，
        // 诊断由资源加载在正常启动/reload 报）。
        var customDir = customThemesDir();
        if (Files.isDirectory(customDir)) {
            try (var stream = Files.list(customDir)) {
                for (var path : stream.filter(p -> p.getFileName() != null
                        && p.getFileName().toString().endsWith(".json")).toList()) {
                    try {
                        var customTheme = loadThemeFromPath(path);
                        if (customTheme.name() != null) {
                            addTheme.accept(new Themes.ThemeInfo(customTheme.name(), path));
                        }
                    } catch (RuntimeException e) {
                        // Invalid themes are ignored here.
                    }
                }
            } catch (IOException e) {
                // 目录读失败与不存在同（pi existsSync 门外的 readdir 抛错不抵此层）。
            }
        }

        for (var entry : Themes.registeredThemesSnapshot().entrySet()) {
            addTheme.accept(new Themes.ThemeInfo(entry.getKey(),
                entry.getValue().sourcePath() != null
                    ? Path.of(entry.getValue().sourcePath()) : null));
        }

        // The system theme comes first: it is the default and adapts to every terminal.
        result.sort(Comparator.comparing(Themes.ThemeInfo::name,
            (a, b) -> SystemTheme.SYSTEM_THEME_NAME.equals(a) ? -1
                : SystemTheme.SYSTEM_THEME_NAME.equals(b) ? 1 : a.compareTo(b)));
        return result;
    }
}
