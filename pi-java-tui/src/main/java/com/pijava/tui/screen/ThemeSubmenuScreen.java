package com.pijava.tui.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.theme.ThemeController;
import com.pijava.coding.agent.theme.ThemeSettings;
import com.pijava.coding.agent.theme.Themes;
import com.pijava.tui.component.SelectList;

import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.event.KeyEvent;

/**
 * 设置页 Theme 子菜单（pi {@code settings-selector.ts} 的 {@code ThemeSubmenu}
 * :254-455 对位，批 21/docs/32 T11；map/05 S9）。
 *
 * <p>两模式：<b>single</b>（一个主题——system 置顶、{@code automatic} 项、其余
 * 主题）与 <b>automatic</b>（Light/Dark 两子选择＋Apply＋Change mode）。高亮移动
 * 即预览（{@link ThemeController#preview}，不持久）；Enter（single）/Apply
 * （automatic）持久化设置串；Esc 还原为打开时的原设置后关闭。</p>
 *
 * <p>差异注记（不登记窄差）：pi 的 SelectSubmenu 有标题/描述双栏；java
 * {@link SelectList} 单栏，描述并入标签文本（ModelThinkingLevelScreen 同族）。</p>
 */
public final class ThemeSubmenuScreen implements ScreenOverlay {

    /** pi {@code AUTOMATIC_THEME_VALUE = "/"}。 */
    private static final String AUTOMATIC_VALUE = "/";

    private enum Mode { SINGLE, AUTOMATIC, PICK_LIGHT, PICK_DARK }

    /** 主题列表项（value＝设置值；label 含 ✓ 标与描述）。 */
    private record ThemeItem(String value, String label) {}

    /** 自动模式菜单行。 */
    private record AutoRow(String id, String label) {}

    private final AgentSession session;
    private final ThemeController controller;
    private final List<String> availableThemes;
    private final String originalThemeSetting;
    private Mode mode;
    private String singleTheme;
    private String lightTheme;
    private String darkTheme;
    private SelectList<ThemeItem> themeList;
    private SelectList<AutoRow> autoList;
    private String lastPreviewed;
    private boolean done;

    /**
     * @param session 当前会话（settings 写回）
     * @param controller 主题控制器（预览/应用；null ⇒ 测试形：只写 settings）
     */
    public ThemeSubmenuScreen(AgentSession session, ThemeController controller) {
        this.session = session;
        this.controller = controller;
        this.availableThemes = Themes.getAvailableThemes();
        // pi：currentValue = config.currentTheme = controller.getThemeSelection()。
        this.originalThemeSetting = controller != null
            ? controller.getThemeSelection()
            : session.services().settings().accessors().getThemeSetting();
        var currentSetting = originalThemeSetting != null ? originalThemeSetting : "";
        var autoTheme = ThemeSettings.parseAutoThemeSetting(currentSetting);
        // pi defaultAutomaticThemes（:242-251）。
        var automaticThemes = autoTheme != null ? autoTheme
            : new ThemeSettings.AutoThemeSetting(
                preferredTheme(currentSetting.contains("/") ? null : currentSetting),
                preferredTheme(currentSetting.contains("/") ? null : currentSetting));
        this.lightTheme = automaticThemes.lightTheme();
        this.darkTheme = automaticThemes.darkTheme();
        this.mode = autoTheme != null ? Mode.AUTOMATIC : Mode.SINGLE;
        // pi ctor：singleTheme = preferred(fixedTheme ?? activeAutomatic)。
        var fixed = autoTheme != null || currentSetting.contains("/") ? null : currentSetting;
        this.singleTheme = preferredTheme(fixed != null ? fixed
            : autoTheme != null ? activeAutomaticTheme() : null);
        if (this.mode == Mode.AUTOMATIC) {
            showAutomaticMenu();
        } else {
            showSingleMenu();
        }
    }

    // ── pi 辅助函数（:236-251）─────────────────────────────────

    /** pi {@code preferredTheme}：preferred ∈ 表 ⇒ 它；否则 system；否则首项。 */
    private String preferredTheme(String preferred) {
        if (preferred != null && availableThemes.contains(preferred)) {
            return preferred;
        }
        if (availableThemes.contains("system")) {
            return "system";
        }
        return availableThemes.isEmpty() ? "system" : availableThemes.get(0);
    }

    private String activeAutomaticTheme() {
        var terminal = controller != null
            ? controller.getTerminalTheme()
            : Themes.terminalTheme();
        return terminal == com.pijava.coding.agent.theme.ThemeAppearance.LIGHT
            ? lightTheme : darkTheme;
    }

    /** pi {@code getAutomaticThemeSetting}：{@code "<light>/<dark>"}。 */
    private String automaticSetting() {
        return lightTheme + "/" + darkTheme;
    }

    private String currentSetting() {
        return mode == Mode.AUTOMATIC || mode == Mode.PICK_LIGHT || mode == Mode.PICK_DARK
            ? automaticSetting() : singleTheme;
    }

    // ── 菜单构造（pi showSingleMenu/showAutomaticMenu）────────────────

    /** pi {@code themeItems}＋{@code singleModeThemeItems}：✓ 标、system 置顶、
     *  automatic 项随 system 之后。 */
    private List<ThemeItem> singleModeItems() {
        var items = new ArrayList<ThemeItem>();
        var themes = new ArrayList<>(availableThemes);
        themes.remove("system");
        items.add(themeItem("system"));
        items.add(new ThemeItem(AUTOMATIC_VALUE,
            "  automatic — Use separate themes for light and dark terminal appearance"));
        for (var name : themes) {
            items.add(themeItem(name));
        }
        return items;
    }

    private ThemeItem themeItem(String name) {
        var label = (name.equals(singleTheme) ? "✓ " : "  ") + name;
        if ("system".equals(name)) {
            label += " — Theme created from your terminal's colors";
        }
        return new ThemeItem(name, label);
    }

    private void showSingleMenu() {
        mode = Mode.SINGLE;
        themeList = new SelectList<>(List.copyOf(singleModeItems()), ThemeItem::label);
        var current = singleTheme;
        themeList.selectItem(item -> item.value().equals(current));
        lastPreviewed = current;
    }

    private void showAutomaticMenu() {
        mode = Mode.AUTOMATIC;
        var rows = List.of(
            new AutoRow("light-theme", "Light theme: " + lightTheme),
            new AutoRow("dark-theme", "Dark theme: " + darkTheme),
            new AutoRow("apply", "Apply — save and go back"),
            new AutoRow("single-mode", "Change mode — switch to single theme"));
        autoList = new SelectList<>(rows, AutoRow::label);
        lastPreviewed = automaticSetting();
    }

    /** pi {@code createThemeSelect}（:418-433）：子选择（light/dark 半对）。 */
    private void showPicker(Mode pickMode) {
        mode = pickMode;
        var current = pickMode == Mode.PICK_LIGHT ? lightTheme : darkTheme;
        var items = availableThemes.stream()
            .map(name -> new ThemeItem(name,
                (name.equals(current) ? "✓ " : "  ") + name))
            .toList();
        themeList = new SelectList<>(List.copyOf(items), ThemeItem::label);
        themeList.selectItem(item -> item.value().equals(current));
        lastPreviewed = automaticSetting();
    }

    // ── 输入 ────────────────────────────────────────────────────

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        if (mode == Mode.AUTOMATIC) {
            return onAutomaticKey(event);
        }
        return onThemeListKey(event);
    }

    /** single/picker 列表键处理：确认/取消/高亮移动预览。 */
    private boolean onThemeListKey(KeyEvent event) {
        if (event.isCancel() && themeList.filter().isEmpty()) {
            onListCancel();
            return true;
        }
        var consumed = themeList.onKeyEvent(event);
        if (themeList.confirmed()) {
            themeList.selected().ifPresent(this::onListConfirm);
        } else if (themeList.cancelled()) {
            onListCancel();
        } else {
            // pi onSelectionChange ⇒ onThemePreview（:54-56/:328）。
            themeList.selected().ifPresent(item -> previewForSelection(item.value()));
        }
        return consumed;
    }

    private void onListConfirm(ThemeItem item) {
        if (mode == Mode.PICK_LIGHT || mode == Mode.PICK_DARK) {
            // pi createThemeSelect onSelect（:425-429）：回写半对＋预览＋返回自动菜单。
            if (mode == Mode.PICK_LIGHT) {
                lightTheme = item.value();
            } else {
                darkTheme = item.value();
            }
            preview(automaticSetting());
            showAutomaticMenu();
            return;
        }
        // single 菜单
        if (AUTOMATIC_VALUE.equals(item.value())) {
            // pi :307-312：切自动模式＋预览当前对。
            showAutomaticMenu();
            preview(automaticSetting());
            return;
        }
        singleTheme = item.value();
        apply(item.value());
    }

    private void onListCancel() {
        if (mode == Mode.PICK_LIGHT || mode == Mode.PICK_DARK) {
            // pi createThemeSelect onCancel（:429-432）：预览当前设置＋返回。
            preview(currentSetting());
            showAutomaticMenu();
            return;
        }
        cancel();
    }

    /** automatic 四行菜单键处理。 */
    private boolean onAutomaticKey(KeyEvent event) {
        if (event.isCancel()) {
            cancel();
            return true;
        }
        var consumed = autoList.onKeyEvent(event);
        if (autoList.confirmed() && autoList.selected().isPresent()) {
            switch (autoList.selected().get().id()) {
                case "light-theme" -> showPicker(Mode.PICK_LIGHT);
                case "dark-theme" -> showPicker(Mode.PICK_DARK);
                case "apply" -> apply(automaticSetting());
                case "single-mode" -> {
                    // pi :390-394：切回 single＋预览当前激活半对。
                    singleTheme = activeAutomaticTheme();
                    preview(singleTheme);
                    showSingleMenu();
                }
                default -> { }
            }
        }
        return consumed;
    }

    // ── 结局 ────────────────────────────────────────────────────

    /** pi {@code apply}（:446-448）：持久化设置串并应用。 */
    private void apply(String themeSetting) {
        var accessors = session.services().settings().accessors();
        accessors.setTheme(themeSetting);
        session.services().settings().flush();
        if (controller != null) {
            controller.setThemeSetting(themeSetting);
        }
        done = true;
    }

    /** pi {@code cancel}（:450-453）：预览还原原设置后退出。 */
    private void cancel() {
        preview(originalThemeSetting);
        done = true;
    }

    /** 预览去重（高亮未动不发——pi 由 SelectList 的 change 事件天然去重）。 */
    private void previewForSelection(String value) {
        var target = AUTOMATIC_VALUE.equals(value) ? automaticSetting() : value;
        if (target.equals(lastPreviewed)) {
            return;
        }
        lastPreviewed = target;
        preview(target);
    }

    private void preview(String themeSettingOrName) {
        if (controller != null) {
            controller.preview(themeSettingOrName);
        }
    }

    @Override
    public boolean isDone() {
        return done;
    }

    @Override
    public void apply(AgentSession session, Consumer<AgentSession> switcher) {
        // 持久化在 Enter/Apply 时已完成（apply(String)）；关屏无追加动作。
    }

    @Override
    public Element render() {
        return mode == Mode.AUTOMATIC ? autoList.render() : themeList.render();
    }
}
