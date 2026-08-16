package com.pijava.tui.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.SettingsAccessors;
import com.pijava.tui.util.TamboUIAdapter;

import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.event.KeyEvent;

/**
 * Settings page (Phase 3 design §8.1): navigate fields with ↑/↓, press Enter
 * to cycle the value of boolean/enum fields, Esc to close. All changes are
 * written to the global settings scope and flushed.
 */
public final class SettingsScreen implements ScreenOverlay {

    private boolean closed;
    private final AgentSession session;
    private int selected;

    // All cyclable enum/boolean settings (Phase 3 design §12.1). Free-form
    // string/list/nested fields need a text-input UX and remain out of scope.
    private static final List<SettingField> FIELDS = List.of(
        new SettingField("Theme", List.of("dark", "light"),
            SettingsAccessors::getTheme, SettingsAccessors::setTheme),
        new SettingField("Steering mode", List.of("one-at-a-time", "all"),
            SettingsAccessors::getSteeringMode, SettingsAccessors::setSteeringMode),
        new SettingField("Follow-up mode", List.of("one-at-a-time", "all"),
            SettingsAccessors::getFollowUpMode, SettingsAccessors::setFollowUpMode),
        new SettingField("Thinking level",
            List.of("off", "minimal", "low", "medium", "high", "xhigh"),
            SettingsAccessors::getDefaultThinkingLevel,
            SettingsAccessors::setDefaultThinkingLevel),
        new SettingField("Project trust", List.of("ask", "always", "never"),
            SettingsAccessors::getDefaultProjectTrust,
            SettingsAccessors::setDefaultProjectTrust),
        new SettingField("TUI mode", List.of("regular", "fullscreen"),
            SettingsAccessors::getTuiMode, SettingsAccessors::setTuiMode),
        new SettingField("Hide thinking block", List.of("false", "true"),
            a -> bool(a.getHideThinkingBlock()),
            (a, v) -> a.setHideThinkingBlock(Boolean.parseBoolean(v))),
        new SettingField("Double-Esc action", List.of("fork", "tree", "none"),
            SettingsAccessors::getDoubleEscapeAction,
            SettingsAccessors::setDoubleEscapeAction),
        new SettingField("Tree filter mode",
            List.of("default", "no-tools", "user-only", "labeled-only", "all"),
            SettingsAccessors::getTreeFilterMode,
            SettingsAccessors::setTreeFilterMode),
        new SettingField("Quiet startup", List.of("false", "true"),
            a -> bool(a.getQuietStartup()),
            (a, v) -> a.setQuietStartup(Boolean.parseBoolean(v))));

    /**
     * Creates the settings page bound to the session's settings service.
     *
     * @param session the session whose settings are edited
     */
    public SettingsScreen(AgentSession session) {
        this.session = session;
    }

    /** Esc closes the page. */
    @Override
    public boolean onKeyEvent(KeyEvent event) {
        if (event.isCancel()) {
            closed = true;
            return true;
        }
        if (event.isUp()) {
            selected = (selected - 1 + FIELDS.size()) % FIELDS.size();
            return true;
        }
        if (event.isDown()) {
            selected = (selected + 1) % FIELDS.size();
            return true;
        }
        if (event.isConfirm()) {
            cycle(selected);
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

    @Override
    public Element render() {
        var rows = new ArrayList<dev.tamboui.toolkit.element.Element>();
        for (int i = 0; i < FIELDS.size(); i++) {
            var field = FIELDS.get(i);
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
        var value = read(field);
        return value == null ? "(unset)" : value;
    }

    private void cycle(int index) {
        var field = FIELDS.get(index);
        var current = read(field);
        var values = field.values();
        var position = current == null ? -1 : values.indexOf(current);
        var next = values.get((position + 1) % values.size());
        write(field, next);
        session.services().settings().flush();
    }

    private String read(SettingField field) {
        return field.getter().apply(accessors());
    }

    private void write(SettingField field, String value) {
        field.setter().accept(accessors(), value);
    }

    private SettingsAccessors accessors() {
        return session.services().settings().accessors();
    }

    private static String bool(Boolean value) {
        return value == null ? null : String.valueOf(value);
    }

    private record SettingField(
        String label,
        List<String> values,
        Function<SettingsAccessors, String> getter,
        BiConsumer<SettingsAccessors, String> setter
    ) {}
}
