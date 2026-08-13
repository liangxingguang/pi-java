package com.pijava.coding.agent.core;

import java.util.HashSet;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Settings lifecycle: load, global/project deep merge, project trust,
 * modified-field tracking and flush (Phase 3 design §12.2).
 *
 * <p>Priority: CLI &gt; project &gt; global. {@link #effective()} recomputes
 * the deep merge on every call so per-field setters (via
 * {@link SettingsAccessors}) take effect immediately without a reload.</p>
 */
public final class SettingsManager {

    private final SettingsStorage storage;
    private Settings global = new Settings();
    private Settings project = new Settings();
    private boolean projectTrusted = true;
    private final Set<String> modifiedFields = new HashSet<>();
    private final SettingsAccessors accessors = new SettingsAccessors(this);

    private SettingsManager(SettingsStorage storage) {
        this.storage = storage;
    }

    /**
     * Load settings from the real filesystem.
     *
     * @param projectTrustOverride {@code --approve}/{@code --no-approve} value
     *                             or null (falls back to the configured default)
     */
    public static SettingsManager load(Boolean projectTrustOverride) {
        var manager = new SettingsManager(new FileSettingsStorage());
        manager.reload();
        manager.setProjectTrusted(
            resolveTrust(projectTrustOverride, manager.merged().defaultProjectTrust));
        return manager;
    }

    /** Create a manager backed by the given storage (tests/in-memory). */
    public static SettingsManager withStorage(SettingsStorage storage) {
        var manager = new SettingsManager(storage);
        manager.reload();
        return manager;
    }

    /** Re-read both scopes from storage and rebuild the merged view. */
    public void reload() {
        global = storage.readGlobal();
        project = storage.readProject();
        migrate(global);
        migrate(project);
        modifiedFields.clear();
    }

    /** Deep-merged effective settings (project overrides global). */
    public Settings effective() {
        return merge(global, project);
    }

    /** The raw global scope (mutated by {@link SettingsAccessors}). */
    Settings global() {
        return global;
    }

    /** The raw project scope. */
    Settings project() {
        return project;
    }

    /** Per-field accessors (design split to stay under 500 lines/file). */
    public SettingsAccessors accessors() {
        return accessors;
    }

    /** Record a field as modified so {@link #flush()} persists it. */
    void markModified(String field) {
        modifiedFields.add(field);
    }

    /** Whether the current project scope is trusted. */
    public boolean isProjectTrusted() {
        return projectTrusted;
    }

    /**
     * Change project trust. When untrusted, the project scope is cleared so
     * only global settings apply (Phase 3 design §12.4).
     */
    public void setProjectTrusted(boolean trusted) {
        this.projectTrusted = trusted;
        if (!trusted) {
            storage.writeProject(new Settings());
        }
        reload();
    }

    /** Persist modified global fields. */
    public void flush() {
        if (!modifiedFields.isEmpty()) {
            storage.writeGlobal(global);
            modifiedFields.clear();
        }
    }

    /** The merged view used by {@link #load} for trust resolution. */
    private Settings merged() {
        return merge(global, project);
    }

    private static boolean resolveTrust(Boolean override, String defaultTrust) {
        if (override != null) {
            return override;
        }
        return !"never".equals(defaultTrust);
    }

    /**
     * Migrate legacy settings fields (Phase 3 design §12.3): {@code queueMode}
     * → {@code steeringMode}, {@code websockets} → {@code transport}. The old
     * keys are consumed (removed from the unknown passthrough) once migrated.
     */
    private static void migrate(Settings settings) {
        if (settings.steeringMode == null) {
            var legacy = settings.unknown().get("queueMode");
            if (legacy instanceof String value) {
                settings.steeringMode = value;
                settings.removeUnknown("queueMode");
            }
        }
        if (settings.transport == null) {
            var legacy = settings.unknown().get("websockets");
            if (legacy instanceof String value) {
                settings.transport = value;
                settings.removeUnknown("websockets");
            }
        }
    }

    private Settings merge(Settings base, Settings override) {
        var baseNode = (ObjectNode) Json.mapper().valueToTree(base);
        var overrideNode = (ObjectNode) Json.mapper().valueToTree(override);
        deepMerge(baseNode, overrideNode);
        return Json.mapper().convertValue(baseNode, Settings.class);
    }

    private static void deepMerge(ObjectNode base, ObjectNode override) {
        var properties = override.properties();
        for (var entry : properties) {
            var value = entry.getValue();
            // A null in the overriding scope means "not set" — fall through
            // to the base (global) value instead of clobbering it.
            if (value.isNull()) {
                continue;
            }
            var current = base.get(entry.getKey());
            if (current instanceof ObjectNode currentObject && value instanceof ObjectNode valueObject) {
                deepMerge(currentObject, valueObject);
            } else {
                base.set(entry.getKey(), value);
            }
        }
    }
}
