package com.pijava.coding.agent.theme;

import java.util.function.Consumer;
import java.util.function.Supplier;

import com.pijava.coding.agent.core.SettingsManager;

/**
 * 交互模式主题控制器（pi {@code theme-controller.ts} 253 行的裁剪对位，
 * 批 21/docs/32 T7，裁决③）。
 *
 * <p>职责：应用 theme 设置并与终端保持同步。主题立即生效；pi 的终端颜色
 * 查询面（OSC 10/11/4、100ms 灰度窗、2031 明暗通知、autoSync）因 TamboUI 无
 * 对应能力整体裁掉（窄差 D123）——外观判定一次性走
 * {@link ThemeSettings#detectTerminalTheme}（COLORFGBG ⇒ dark）。</p>
 *
 * <p>设置与持久化分两层：本控制器只驱动 {@link Themes} 运行时态；写 settings
 * 由调用方（设置页/扩展面）做（pi 同——controller 不直接写 settings）。</p>
 */
public final class ThemeController {

    private final Supplier<SettingsManager> getSettingsManager;
    private final Consumer<String> showError;
    private final Runnable onChanged;
    private String currentThemeSetting;
    private String activeThemeName;

    /**
     * @param getSettingsManager settings 晚绑定读取口
     * @param showError 错误展示口（主题加载失败提示）
     * @param onChanged 主题变化回调（TUI 重渲染/边框色更新）
     * @param initialThemeSetting {@code --use-theme} 值（本次调用覆盖，不持久）
     */
    public ThemeController(Supplier<SettingsManager> getSettingsManager,
                           Consumer<String> showError, Runnable onChanged,
                           String initialThemeSetting) {
        this.getSettingsManager = getSettingsManager;
        this.showError = showError;
        this.onChanged = onChanged;
        this.currentThemeSetting = initialThemeSetting;
        this.activeThemeName = resolveThemeName();
        Themes.initTheme(this.activeThemeName, true);
    }

    /**
     * pi {@code applyFromSettings}（:103-109 裁剪版）：解析当前设置并应用——
     主题对与 system 主题在 pi 会跟随终端外观变化（java 裁：无外观事件源）。
     */
    public void applyFromSettings() {
        var themeName = resolveThemeName();
        applyThemeName(themeName, getThemeSetting() != null);
    }

    /** pi {@code getThemeSelection}（:120-122）。 */
    public String getThemeSelection() {
        var selection = currentThemeSetting != null ? currentThemeSetting
            : getSettingsManager.get().accessors().getThemeSetting();
        return selection != null ? selection : activeThemeName;
    }

    /**
     * pi {@code setThemeName}（:124-131）：按名切换；成功则记为当前设置
     *（单名——持久化由调用方）。
     */
    public Themes.ThemeResult setThemeName(String themeName, boolean showError) {
        var result = applyThemeName(themeName, showError);
        if (result.success()) {
            this.currentThemeSetting = themeName;
        }
        return result;
    }

    /** pi {@code setThemeSetting}（:133-136）：设置串（可为对形）＋重应用。 */
    public void setThemeSetting(String themeSetting) {
        this.currentThemeSetting = themeSetting;
        applyFromSettings();
    }

    /** pi {@code setThemeInstance}（:138-144）。 */
    public Themes.ThemeResult setThemeInstance(Theme themeInstance) {
        Themes.setThemeInstance(themeInstance);
        this.activeThemeName = "<in-memory>";
        notifyChanged();
        return Themes.ThemeResult.ok();
    }

    /**
     * pi {@code preview}（:146-153）：选择器悬停预览——应用但不记设置；
     * 恢复由调用方预览原设置完成。
     */
    public void preview(String themeSettingOrName) {
        var themeName = ThemeSettings.resolveThemeSetting(themeSettingOrName,
            Themes.terminalTheme());
        if (themeName == null) {
            themeName = this.activeThemeName;
        }
        if (themeName == null) {
            return;
        }
        if (Themes.setTheme(themeName, true).success()) {
            notifyChanged();
        }
    }

    /** pi {@code getTerminalTheme}（:165-167）。 */
    public ThemeAppearance getTerminalTheme() {
        return Themes.terminalTheme();
    }

    private String getThemeSetting() {
        return currentThemeSetting != null ? currentThemeSetting
            : getSettingsManager.get().accessors().getThemeSetting();
    }

    /** pi {@code resolveThemeName}（:174-176）：无设置 ⇒ system。 */
    private String resolveThemeName() {
        var resolved = ThemeSettings.resolveThemeSetting(getThemeSetting(),
            Themes.terminalTheme());
        return resolved != null ? resolved : SystemTheme.SYSTEM_THEME_NAME;
    }

    private Themes.ThemeResult applyThemeName(String themeName, boolean showErrorToo) {
        var result = Themes.setTheme(themeName, true);
        this.activeThemeName = result.success() ? themeName : SystemTheme.SYSTEM_THEME_NAME;
        notifyChanged();
        if (!result.success() && showErrorToo) {
            showError.accept("Failed to load theme \"" + themeName + "\": " + result.error()
                + "\nFell back to the system theme.");
        }
        return result;
    }

    private void notifyChanged() {
        onChanged.run();
    }
}
