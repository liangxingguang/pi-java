package com.pijava.coding.agent.core;

/**
 * In-memory settings storage for tests and processes without a home
 * directory (Phase 3 design §12.3).
 */
public final class InMemorySettingsStorage implements SettingsStorage {

    private Settings global = new Settings();
    private Settings project = new Settings();

    @Override
    public Settings readGlobal() {
        return global;
    }

    @Override
    public Settings readProject() {
        return project;
    }

    @Override
    public void writeGlobal(Settings settings) {
        this.global = settings;
    }

    @Override
    public void writeProject(Settings settings) {
        this.project = settings;
    }
}
