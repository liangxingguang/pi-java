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
     * Run a read-modify-write function under a scope lock. {@code fn} receives
     * the current content <b>as JSON text</b> and returns the new content
     * (or {@code null} to skip the write).
     *
     * <p>批 19 修默认实现：旧默认把 {@code Settings.toString()}（非 JSON）喂给
     * 回调且丢弃返回值——现在做完整 JSON 往返（序列化 → fn → 反序列化写回），
     * 内存存储也能行使批 19 的字段级 merge 写回链。</p>
     */
    default void withLock(SettingsScope scope, Function<String, String> fn) {
        var global = scope instanceof SettingsScope.Global;
        try {
            var current = Json.mapper().writeValueAsString(
                global ? readGlobal() : readProject());
            var updated = fn.apply(current);
            if (updated != null) {
                var parsed = Json.mapper().readValue(updated, Settings.class);
                if (global) {
                    writeGlobal(parsed);
                } else {
                    writeProject(parsed);
                }
            }
        } catch (java.io.IOException e) {
            throw new SettingsStorageException("Cannot read-modify-write settings", e);
        }
    }
}
