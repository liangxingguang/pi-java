package com.pijava.tui.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.Settings;
import com.pijava.coding.agent.core.SettingsAccessors;
import com.pijava.tui.util.TamboUIAdapter;

import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.event.KeyEvent;

/**
 * Settings page (Phase 3 design §8.1): navigate fields with ↑/↓, press Enter
 * to cycle the value of boolean/enum fields, Esc to close. All changes are
 * written to the global settings scope and flushed.
 *
 * <p>批 12（docs/23）：字段改为实例装配（getter/setter 闭包可跨访问器面——
 * {@code accessors()}/{@code uiPrefs()}/{@code install()}），新增 outputPad、
 * collapseChangelog、showHardwareCursor、fullscreenScrollbar、warnings 行与
 * "Default thinking level per model" 子菜单入口（T14）。</p>
 */
public final class SettingsScreen implements ScreenOverlay {

    private boolean closed;
    private final AgentSession session;
    private final List<SettingField> fields;
    private int selected;
    /** 批 12 T14：Enter 命中子菜单行置位；PiTuiApp 检出后改开 ModelThinkingLevelScreen。 */
    private boolean modelThinkingRequested;

    /**
     * Creates the settings page bound to the session's settings service.
     *
     * @param session the session whose settings are edited
     */
    public SettingsScreen(AgentSession session) {
        this.session = session;
        this.fields = buildFields(session);
    }

    // All cyclable enum/boolean settings (Phase 3 design §12.1). Free-form
    // string/list/nested fields need a text-input UX and remain out of scope.
    private static List<SettingField> buildFields(AgentSession session) {
        var accessors = session.services().settings().accessors();
        var uiPrefs = session.services().settings().uiPrefs();
        var install = session.services().settings().install();
        var fields = new ArrayList<SettingField>();
        fields.add(new SettingField("Theme", List.of("dark", "light"),
            accessors::getTheme, accessors::setTheme));
        fields.add(new SettingField("Steering mode", List.of("one-at-a-time", "all"),
            accessors::getSteeringMode, accessors::setSteeringMode));
        fields.add(new SettingField("Follow-up mode", List.of("one-at-a-time", "all"),
            accessors::getFollowUpMode, accessors::setFollowUpMode));
        fields.add(new SettingField("Thinking level",
            List.of("off", "minimal", "low", "medium", "high", "xhigh"),
            accessors::getDefaultThinkingLevel, accessors::setDefaultThinkingLevel));
        fields.add(new SettingField("Project trust", List.of("ask", "always", "never"),
            accessors::getDefaultProjectTrust, accessors::setDefaultProjectTrust));
        fields.add(new SettingField("TUI mode", List.of("regular", "fullscreen"),
            accessors::getTuiMode, accessors::setTuiMode));
        fields.add(new SettingField("Hide thinking block", List.of("false", "true"),
            () -> bool(accessors.getHideThinkingBlock()),
            v -> accessors.setHideThinkingBlock(Boolean.parseBoolean(v))));
        fields.add(new SettingField("Double-Esc action", List.of("fork", "tree", "none"),
            accessors::getDoubleEscapeAction, accessors::setDoubleEscapeAction));
        fields.add(new SettingField("Tree filter mode",
            List.of("default", "no-tools", "user-only", "labeled-only", "all"),
            accessors::getTreeFilterMode, accessors::setTreeFilterMode));
        fields.add(new SettingField("Quiet startup", List.of("false", "true"),
            () -> bool(accessors.getQuietStartup()),
            v -> accessors.setQuietStartup(Boolean.parseBoolean(v))));
        // B244（pi settings-selector.ts:797-806）："Skill commands" 开关。
        fields.add(new SettingField("Skill commands", List.of("false", "true"),
            () -> String.valueOf(accessors.getEnableSkillCommands()),
            v -> accessors.setEnableSkillCommands(Boolean.parseBoolean(v))));
        // ── 批 12（docs/23 §3）：消费键行 ─────────────────────────
        // T6（pi settings-selector outputPad 行对位）。
        fields.add(new SettingField("Output pad", List.of("1", "0"),
            () -> String.valueOf(uiPrefs.getOutputPad()),
            v -> uiPrefs.setOutputPad(Integer.parseInt(v))));
        // T7（install 域；pi :843-872 折叠门）。
        fields.add(new SettingField("Collapse changelog", List.of("false", "true"),
            () -> String.valueOf(install.getCollapseChangelog()),
            v -> install.setCollapseChangelog(Boolean.parseBoolean(v))));
        // T9。
        fields.add(new SettingField("Hardware cursor", List.of("false", "true"),
            () -> String.valueOf(uiPrefs.getShowHardwareCursor()),
            v -> uiPrefs.setShowHardwareCursor(Boolean.parseBoolean(v))));
        // T11（auto/always/hidden 循环；渲染期现读热更）。
        fields.add(new SettingField("Fullscreen scrollbar", List.of("auto", "always", "hidden"),
            uiPrefs::getFullscreenScrollbar, uiPrefs::setFullscreenScrollbar));
        // T13（pi settings-selector.ts:157-176 warnings 子菜单对位——单行开关）。
        fields.add(new SettingField("Warn Anthropic extra usage", List.of("true", "false"),
            () -> String.valueOf(
                !Boolean.FALSE.equals(uiPrefs.getWarnings().anthropicExtraUsage())),
            v -> uiPrefs.setWarnings(new Settings.Warnings(Boolean.parseBoolean(v)))));
        // T14（pi settings-selector.ts:607-610 菜单项对位）：子菜单行——Enter
        // 不循环取值，置 modelThinkingRequested 由 PiTuiApp 改开两级选择器。
        fields.add(SettingField.submenu("Default thinking level per model",
            () -> modelThinkingSummary(accessors)));
        return List.copyOf(fields);
    }

    /** pi {@code modelThinkingOverridesSummary} 对位：override 计数摘要。 */
    private static String modelThinkingSummary(SettingsAccessors accessors) {
        int count = accessors.getAllModelThinkingLevels().size();
        return count == 0 ? "(none)" : count + " override" + (count > 1 ? "s" : "");
    }

    /** Esc closes the page. */
    @Override
    public boolean onKeyEvent(KeyEvent event) {
        if (event.isCancel()) {
            closed = true;
            return true;
        }
        if (event.isUp()) {
            selected = (selected - 1 + fields.size()) % fields.size();
            return true;
        }
        if (event.isDown()) {
            selected = (selected + 1) % fields.size();
            return true;
        }
        if (event.isConfirm()) {
            var field = fields.get(selected);
            if (field.submenu()) {
                modelThinkingRequested = true;
            } else {
                cycle(selected);
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean isDone() {
        return closed;
    }

    @Override
    public void apply(AgentSession session, Consumer<AgentSession> switcher) {
        // Edits are applied on Enter; nothing else happens on close.
    }

    /**
     * 批 12 T14：子菜单请求的一次性检出（PiTuiApp 的 overlay 完成处理点调用；
     * 返回 true 后复位）。
     */
    public boolean pollModelThinkingRequest() {
        var requested = modelThinkingRequested;
        modelThinkingRequested = false;
        return requested;
    }

    @Override
    public Element render() {
        var rows = new ArrayList<dev.tamboui.toolkit.element.Element>();
        for (int i = 0; i < fields.size(); i++) {
            var field = fields.get(i);
            var text = TamboUIAdapter.text(
                (i == selected ? "▶ " : "  ")
                    + field.label() + ": " + currentValue(field));
            rows.add(i == selected ? text.cyan() : text);
        }
        return TamboUIAdapter.panel(
            "Settings (↑/↓ navigate, Enter cycle, Esc close)",
            TamboUIAdapter.column(rows))
            .fill();
    }

    private String currentValue(SettingField field) {
        var value = field.getter().get();
        return value == null ? "(unset)" : value;
    }

    private void cycle(int index) {
        var field = fields.get(index);
        var current = field.getter().get();
        var values = field.values();
        var position = current == null ? -1 : values.indexOf(current);
        var next = values.get((position + 1) % values.size());
        field.setter().accept(next);
        session.services().settings().flush();
    }

    private static String bool(Boolean value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 一行设置：循环取值字段，或 {@code submenu} 入口（values 空、Enter 置请求位）。 */
    private record SettingField(
        String label,
        List<String> values,
        Supplier<String> getter,
        Consumer<String> setter,
        boolean submenu
    ) {
        SettingField(String label, List<String> values,
                     Supplier<String> getter, Consumer<String> setter) {
            this(label, values, getter, setter, false);
        }

        static SettingField submenu(String label, Supplier<String> summary) {
            return new SettingField(label, List.of(), summary, v -> { }, true);
        }
    }
}
