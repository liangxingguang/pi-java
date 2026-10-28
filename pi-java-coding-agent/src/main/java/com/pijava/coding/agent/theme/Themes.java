package com.pijava.coding.agent.theme;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全局主题注册表（pi {@code theme.ts} 的全局面 :379-876 对位，批 21/docs/32 T7）——
 * pi 用 {@code globalThis} Symbol 跨模块加载器共享；java 模块内唯一类加载器，
 * 静态持有者天然同义。
 *
 * <p><b>裁决⑤（窄差 D126）</b>：{@link #current()} 未初始化时懒初始化内建
 * dark 主题（pi 的 proxy 抛 {@code Theme not initialized}——java 存量测试生态
 * 不经主题初始化，抛错不可行）。</p>
 *
 * <p>线程模型：所有静态态经 {@code Themes} 类锁守卫；主题对象不可变，替换
 * 不换内容（pi「replaced, never mutated」）。</p>
 */
public final class Themes {

    private Themes() {}

    /** pi {@code ThemeResult = { success, error? } }。 */
    public record ThemeResult(boolean success, String error) {
        /** 成功。 */
        static ThemeResult ok() {
            return new ThemeResult(true, null);
        }

        /** 失败（含文案）。 */
        static ThemeResult fail(String error) {
            return new ThemeResult(false, error);
        }
    }

    /** pi {@code ThemeInfo = { name, path } }（path 可空——system 无文件）。 */
    public record ThemeInfo(String name, Path path) {}

    // ── 静态态（pi theme.ts:131-137/666-670）────────────────────────────
    private static TerminalColors terminalColors = TerminalColors.EMPTY;
    private static ThemeAppearance terminalColorScheme;
    private static Theme current;
    private static String currentThemeName;
    private static Runnable onThemeChangeCallback;
    private static final Map<String, Theme> REGISTERED_THEMES = new LinkedHashMap<>();
    private static ThemeWatcher watcher;
    private static TerminalColorMode colorMode = ThemeColorMath.detectTerminalColorMode(null);
    private static final String SYSTEM_THEME_NAME = SystemTheme.SYSTEM_THEME_NAME;

    // ============================================================================
    // 终端色记录（pi :143-156）
    // ============================================================================

    /**
     * 记录终端上报色（裁决③的注入缝——java 无 OSC 查询面，窄差 D123）。
     * 主题对 {@code ""} 令牌用这里的默认色；system 主题由全量生成。
     */
    public static synchronized void setTerminalColors(TerminalColors colors) {
        terminalColors = colors;
    }

    /** 当前终端上报色（恒非 null）。 */
    public static synchronized TerminalColors terminalColors() {
        return terminalColors;
    }

    /** pi {@code setTerminalColorScheme}——终端的明暗上报（无背景上报时的回落）。 */
    public static synchronized void setTerminalColorScheme(ThemeAppearance scheme) {
        terminalColorScheme = scheme;
    }

    /** pi {@code getTerminalTheme}（:639-641）：从至今已上报的一切判明暗。 */
    public static synchronized ThemeAppearance terminalTheme() {
        return ThemeSettings.detectTerminalTheme(terminalColors, terminalColorScheme);
    }

    /** 主题构造用的色彩档（裁决④；模式层可在初始化前用 settings 覆盖重探测）。 */
    public static synchronized TerminalColorMode colorMode() {
        return colorMode;
    }

    /** 模式层入口：带 settings {@code terminal.trueColor} 覆盖重探测色彩档。 */
    public static synchronized void redetectColorMode(Boolean trueColorOverride) {
        colorMode = ThemeColorMath.detectTerminalColorMode(trueColorOverride);
    }

    // ============================================================================
    // 全局实例与切换（pi :682-731）
    // ============================================================================

    /**
     * pi {@code initTheme}（:682-696 逐字）：未知名/非法主题 ⇒ 静默回落 system
     *（currentThemeName 一并回落）；{@code enableWatcher} 只对交互模式开。
     */
    public static synchronized void initTheme(String themeName, boolean enableWatcher) {
        var name = themeName != null ? themeName : SYSTEM_THEME_NAME;
        currentThemeName = name;
        try {
            setGlobalTheme(ThemeCatalog.loadTheme(name));
            if (enableWatcher) {
                startThemeWatcher();
            }
        } catch (RuntimeException e) {
            // Theme is invalid - fall back to the system theme silently
            currentThemeName = SYSTEM_THEME_NAME;
            setGlobalTheme(ThemeCatalog.loadTheme(SYSTEM_THEME_NAME));
            // Don't start watcher for fallback theme
        }
    }

    /**
     * pi {@code setTheme}（:698-719 逐字）：失败 ⇒ 回落 system 且
     * {@code success=false}（带错误文案）；成功 ⇒ 发 {@code onThemeChange}。
     */
    public static synchronized ThemeResult setTheme(String name, boolean enableWatcher) {
        currentThemeName = name;
        try {
            setGlobalTheme(ThemeCatalog.loadTheme(name));
            if (enableWatcher) {
                startThemeWatcher();
            }
            if (onThemeChangeCallback != null) {
                onThemeChangeCallback.run();
            }
            return ThemeResult.ok();
        } catch (RuntimeException e) {
            currentThemeName = SYSTEM_THEME_NAME;
            setGlobalTheme(ThemeCatalog.loadTheme(SYSTEM_THEME_NAME));
            return ThemeResult.fail(e.getMessage() != null ? e.getMessage() : String.valueOf(e));
        }
    }

    /** pi {@code setThemeInstance}（:721-728）：内存实例；watcher 停（无文件可看）。 */
    public static synchronized void setThemeInstance(Theme themeInstance) {
        setGlobalTheme(themeInstance);
        currentThemeName = "<in-memory>";
        stopThemeWatcher();
        if (onThemeChangeCallback != null) {
            onThemeChangeCallback.run();
        }
    }

    /** pi {@code onThemeChange}。 */
    public static synchronized void onThemeChange(Runnable callback) {
        onThemeChangeCallback = callback;
    }

    /** pi {@code setRegisteredThemes}（:672-680）：清表重灌，名前过名校验。 */
    public static synchronized void setRegisteredThemes(List<Theme> themes) {
        REGISTERED_THEMES.clear();
        for (var theme : themes) {
            if (theme.name() != null) {
                assertThemeNameIsValid(theme.name());
                REGISTERED_THEMES.put(theme.name(), theme);
            }
        }
    }

    /** {@link ThemeCatalog} 用的注册表快照（{@code REGISTERED_THEMES} 由本类锁独占持有；
     *  快照保序拷贝，读出即与本类锁脱钩）。 */
    static synchronized Map<String, Theme> registeredThemesSnapshot() {
        return new LinkedHashMap<>(REGISTERED_THEMES);
    }

    /** pi {@code assertThemeNameIsValid}（:461-467，文案逐字）。 */
    static void assertThemeNameIsValid(String name) {
        if (name.contains("/")) {
            throw new IllegalArgumentException(
                "Invalid theme name \"" + name + "\": theme names cannot contain \"/\""
                    + " because it is reserved for automatic light/dark theme settings.");
        }
    }

    /** 当前主题（裁决⑤：未初始化 ⇒ 懒初始化内建 dark，pi 抛错的对位窄差 D126）。 */
    public static synchronized Theme current() {
        if (current == null) {
            setGlobalTheme(ThemeCatalog.loadTheme("dark"));
            currentThemeName = "dark";
        }
        return current;
    }

    /** 当前主题名（未初始化 ⇒ null）。 */
    public static synchronized String currentThemeName() {
        return currentThemeName;
    }

    /** 测试/关机钩：清空全局态（watcher 先停）。 */
    public static synchronized void resetForTests() {
        stopThemeWatcher();
        current = null;
        currentThemeName = null;
        onThemeChangeCallback = null;
        REGISTERED_THEMES.clear();
        terminalColors = TerminalColors.EMPTY;
        terminalColorScheme = null;
    }

    private static void setGlobalTheme(Theme theme) {
        current = theme;
    }

    // ============================================================================
    // 监视器（pi startThemeWatcher/stopThemeWatcher :734-819）
    // ============================================================================

    private static void startThemeWatcher() {
        stopThemeWatcher();
        // Only watch if it's a custom theme (not built-in)
        if (currentThemeName == null || currentThemeName.equals("dark")
                || currentThemeName.equals("light")
                || currentThemeName.equals(SYSTEM_THEME_NAME)) {
            return;
        }
        var watchedName = currentThemeName;
        var themeFile = ThemeCatalog.customThemesDir().resolve(watchedName + ".json");
        if (!Files.exists(themeFile)) {
            return;
        }
        watcher = new ThemeWatcher(ThemeCatalog.customThemesDir(), watchedName, () -> {
            synchronized (Themes.class) {
                // Ignore stale timers after switching themes（pi :764-766 双保险）
                if (!watchedName.equals(currentThemeName)) {
                    return;
                }
                // Keep the last successfully loaded theme active if the file is
                // temporarily missing（pi :769-771）
                if (!Files.exists(themeFile)) {
                    return;
                }
                try {
                    var reloadedTheme = ThemeCatalog.loadThemeFromPath(themeFile);
                    REGISTERED_THEMES.put(watchedName, reloadedTheme);
                    setGlobalTheme(reloadedTheme);
                    if (onThemeChangeCallback != null) {
                        onThemeChangeCallback.run();
                    }
                } catch (RuntimeException e) {
                    // Ignore errors (file might be in invalid state while being edited)
                }
            }
        });
        watcher.start();
    }

    /** pi {@code stopThemeWatcher}。 */
    public static synchronized void stopThemeWatcher() {
        if (watcher != null) {
            watcher.close();
            watcher = null;
        }
    }

    // ============================================================================
    // HTML 导出辅助（pi :825-876）
    // ============================================================================

    /**
     * pi {@code getResolvedThemeColors}（:829-832）：解析后的主题色表 → CSS 兼容
     * hex 串（HTML 导出生成 CSS 自定义属性用）。
     */
    public static Map<String, String> getResolvedThemeColors(String themeName) {
        var colors = ThemeCatalog.loadTheme(themeName != null ? themeName
            : currentThemeName != null ? currentThemeName : SYSTEM_THEME_NAME).colors();
        var result = new LinkedHashMap<String, String>();
        for (var entry : colors.entrySet()) {
            result.put(entry.getKey().piName(), ThemeColorMath.colorToHex(entry.getValue()));
        }
        return result;
    }

    /** pi {@code isLightTheme}（:837-839）。 */
    public static boolean isLightTheme(String themeName) {
        return ThemeCatalog.loadTheme(themeName != null ? themeName
            : currentThemeName != null ? currentThemeName : SYSTEM_THEME_NAME)
                .appearance() == ThemeAppearance.LIGHT;
    }

    /**
     * pi {@code getThemeExportColors}（:845-876 逐字）：主题 JSON 的显式导出
     色——system 恒空；number ⇒ 索引色转 hex；{@code ""} ⇒ null；{@code okhsl(}
     ⇒ 转 hex（CSS 不支持 okhsl()）；其余字符串原样（hex/oklch CSS 懂）。
     * 加载失败 ⇒ 全空。
     */
    public static ThemeJson.ExportColors getThemeExportColors(String themeName) {
        var name = themeName != null ? themeName
            : currentThemeName != null ? currentThemeName : SYSTEM_THEME_NAME;
        if (SYSTEM_THEME_NAME.equals(name)) {
            return new ThemeJson.ExportColors(null, null, null);
        }
        try {
            var themeJson = ThemeCatalog.loadThemeJson(name);
            if (themeJson.export() == null) {
                return new ThemeJson.ExportColors(null, null, null);
            }
            var vars = themeJson.vars();
            return new ThemeJson.ExportColors(
                resolveExport(themeJson.export().pageBg(), vars),
                resolveExport(themeJson.export().cardBg(), vars),
                resolveExport(themeJson.export().infoBg(), vars));
        } catch (RuntimeException e) {
            return new ThemeJson.ExportColors(null, null, null);
        }
    }

    private static String resolveExport(Object value, Map<String, Object> vars) {
        if (value == null) {
            return null;
        }
        var resolved = Theme.resolveVarRefs(value, vars, new java.util.LinkedHashSet<>());
        if (resolved instanceof Number number) {
            return ThemeColorMath.colorToHex(ThemeColor.indexed(number.intValue()));
        }
        var text = (String) resolved;
        if (text.isEmpty()) {
            return null;
        }
        if (text.matches("(?i)^okhsl\\(.*")) {
            return ThemeColorMath.colorToHex(ThemeColorMath.parseColor(text));
        }
        return text;
    }
}
