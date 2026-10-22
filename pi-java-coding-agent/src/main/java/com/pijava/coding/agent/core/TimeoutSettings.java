package com.pijava.coding.agent.core;

/**
 * pi {@code http-dispatcher.ts:5/20-36} ＋ {@code settings-manager.ts:147-157} 对位：
 * {@code number | "disabled"} 联合超时设置的解析与默认值。
 */
public final class TimeoutSettings {

    private TimeoutSettings() {}

    /** pi {@code DEFAULT_HTTP_IDLE_TIMEOUT_MS}（http-dispatcher.ts:5）。 */
    public static final long DEFAULT_HTTP_IDLE_TIMEOUT_MS = 300_000;

    /** pi {@code parseHttpIdleTimeoutMs}（:20-36）："disabled"⇒0；空串/非法/负 ⇒ 空。 */
    public static java.util.OptionalLong parseHttpIdleTimeoutMs(Object value) {
        if (value instanceof String s) {
            var trimmed = s.trim();
            if (trimmed.equalsIgnoreCase("disabled")) {
                return java.util.OptionalLong.of(0);
            }
            if (trimmed.isEmpty()) {
                return java.util.OptionalLong.empty();
            }
            try {
                return parseHttpIdleTimeoutMs(Double.parseDouble(trimmed));
            } catch (NumberFormatException e) {
                return java.util.OptionalLong.empty();
            }
        }
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue())
                || n.doubleValue() < 0) {
            return java.util.OptionalLong.empty();
        }
        return java.util.OptionalLong.of((long) Math.floor(n.doubleValue()));
    }

    /** pi {@code parseTimeoutSetting}（settings-manager.ts:147-157）：非法值抛逐字。 */
    public static java.util.OptionalLong parseTimeoutSetting(Object value, String settingName) {
        var parsed = parseHttpIdleTimeoutMs(value);
        if (parsed.isPresent()) {
            return parsed;
        }
        if (value != null) {
            throw new IllegalArgumentException(
                "Invalid " + settingName + " setting: " + value);
        }
        return java.util.OptionalLong.empty();
    }
}
