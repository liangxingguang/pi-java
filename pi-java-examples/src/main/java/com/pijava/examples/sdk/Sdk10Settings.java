package com.pijava.examples.sdk;

import java.util.List;

/**
 * pi {@code examples/sdk/10-settings.ts} 等价改写（docs/56 §2.1）——
 * SettingsManager 覆盖/持久化/错误上报/内存测试配置投影（形状差 D247）。
 */
public final class Sdk10Settings {

    public record SettingsError(String scope, String message) {}

    /** pi L17-20 applyOverrides 投影。 */
    public record Overrides(boolean compactionEnabled, boolean retryEnabled,
                            int maxRetries, long baseDelayMs) {}

    public static Overrides overrides() {
        return new Overrides(false, true, 5, 1000L);
    }

    /** pi L43 inMemory 测试配置投影。 */
    public static Overrides inMemoryOverrides() {
        return new Overrides(false, false, 0, 0L);
    }

    /** pi L38 console.warn 逐字。 */
    public static String warning(SettingsError e) {
        return "Warning (" + e.scope() + " settings): " + e.message();
    }

    public static final List<String> MESSAGES = List.of(
        "Session created with custom settings",
        "Test session created with in-memory settings");
}
