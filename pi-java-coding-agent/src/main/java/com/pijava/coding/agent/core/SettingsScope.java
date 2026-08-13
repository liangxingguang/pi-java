package com.pijava.coding.agent.core;

/**
 * Settings scope selector (Phase 3 design §12.3).
 */
public sealed interface SettingsScope {

    /** The user-global scope ({@code ~/.pi-java/agent/settings.json}). */
    record Global() implements SettingsScope {}

    /** The per-project scope ({@code <cwd>/.pi-java/settings.json}). */
    record Project() implements SettingsScope {}

    SettingsScope GLOBAL = new Global();
    SettingsScope PROJECT = new Project();
}
