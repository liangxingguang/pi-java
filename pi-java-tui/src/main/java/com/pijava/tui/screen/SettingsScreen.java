package com.pijava.tui.screen;

import java.util.List;
import java.util.ArrayList;
import java.util.function.Consumer;

import com.pijava.coding.agent.core.AgentSession;
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

    private static final List<SettingField> FIELDS = List.of(
        new SettingField("Theme", "theme", List.of("dark", "light")),
        new SettingField("Steering mode", "steeringMode",
            List.of("one-at-a-time", "all")),
        new SettingField("Follow-up mode", "followUpMode",
            List.of("one-at-a-time", "all")),
        new SettingField("Project trust", "defaultProjectTrust",
            List.of("ask", "always", "never")),
        new SettingField("TUI mode", "tuiMode",
            List.of("regular", "fullscreen")),
        new SettingField("Quiet startup", "quietStartup",
            List.of("false", "true")));

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
        var value = read(field.key());
        return value == null ? "(unset)" : value;
    }

    private void cycle(int index) {
        var field = FIELDS.get(index);
        var current = read(field.key());
        var values = field.values();
        var position = current == null ? -1 : values.indexOf(current);
        var next = values.get((position + 1) % values.size());
        write(field.key(), next);
        session.services().settings().flush();
    }

    private String read(String key) {
        var accessors = session.services().settings().accessors();
        return switch (key) {
            case "theme" -> accessors.getTheme();
            case "steeringMode" -> accessors.getSteeringMode();
            case "followUpMode" -> accessors.getFollowUpMode();
            case "defaultProjectTrust" -> accessors.getDefaultProjectTrust();
            case "tuiMode" -> accessors.getTuiMode();
            case "quietStartup" ->
                accessors.getQuietStartup() == null
                    ? null : String.valueOf(accessors.getQuietStartup());
            default -> null;
        };
    }

    private void write(String key, String value) {
        var accessors = session.services().settings().accessors();
        switch (key) {
            case "theme" -> accessors.setTheme(value);
            case "steeringMode" -> accessors.setSteeringMode(value);
            case "followUpMode" -> accessors.setFollowUpMode(value);
            case "defaultProjectTrust" -> accessors.setDefaultProjectTrust(value);
            case "tuiMode" -> accessors.setTuiMode(value);
            case "quietStartup" -> accessors.setQuietStartup(Boolean.parseBoolean(value));
            default -> { }
        }
    }

    private record SettingField(String label, String key, List<String> values) {}
}
