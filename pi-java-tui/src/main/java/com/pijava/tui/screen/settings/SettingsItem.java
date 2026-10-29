package com.pijava.tui.screen.settings;

import java.util.List;

/** One row in the pi-compatible settings list. */
public record SettingsItem(String id, String label, String description,
                           String currentValue, List<String> values, Submenu submenu) {

    /** Submenu type hosted by SettingsScreen. */
    public enum Submenu { NONE, WARNINGS, THEME, MODEL_THINKING }

    /** Canonical constructor; defensively copies the value list. */
    public SettingsItem {
        values = List.copyOf(values);
    }

    /** A row whose Enter/Space action cycles values. */
    public static SettingsItem cycle(String id, String label, String description,
                                     String value, List<String> values) {
        return new SettingsItem(id, label, description, value, values, Submenu.NONE);
    }

    /** A row whose Enter action opens a submenu. */
    public static SettingsItem submenu(String id, String label, String description,
                                       String value, Submenu submenu) {
        return new SettingsItem(id, label, description, value, List.of(), submenu);
    }

    /** Replace the displayed value without losing any other component. */
    public SettingsItem withValue(String value) {
        return new SettingsItem(id, label, description, value, values, submenu);
    }
}
