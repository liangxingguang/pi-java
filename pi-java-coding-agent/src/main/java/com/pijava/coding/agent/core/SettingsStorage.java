package com.pijava.coding.agent.core;

import java.util.function.Function;

/**
 * Persistence boundary for settings (Phase 3 design §12.3).
 *
 * <p>Implementations read/write global and project scope files. The
 * {@link #withLock} hook enables atomic read-modify-write sequences for
 * concurrent processes.</p>
 */
public interface SettingsStorage {

    /** Read the global settings ({@code ~/.pi-java/agent/settings.json}). */
    Settings readGlobal();

    /** Read the project settings ({@code <cwd>/.pi-java/settings.json}). */
    Settings readProject();

    /** Write the global settings file. */
    void writeGlobal(Settings settings);

    /** Write the project settings file. */
    void writeProject(Settings settings);

    /**
     * Run a read-modify-write function under a scope lock.
     *
     * @param scope which file to lock
     * @param fn    receives the current content and returns the new content
     */
    default void withLock(SettingsScope scope, Function<String, String> fn) {
        // Phase 3 default: no cross-process lock (in-memory and simple file
        // storages are single-process). Cross-process FileLock → Phase 4.
        fn.apply(scope instanceof SettingsScope.Global
            ? readGlobal().toString() : readProject().toString());
    }
}
