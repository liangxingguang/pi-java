package com.pijava.coding.agent.core;

/**
 * 网络面设置访问器（批 11 裁决⑧的 {@link SettingsAccessors} 拆分）：
 * {@code httpIdleTimeoutMs}/{@code websocketConnectTimeoutMs}/{@code cacheWarming}。
 */
public final class SettingsNetworkAccessors {

    private final SettingsManager manager;

    SettingsNetworkAccessors(SettingsManager manager) {
        this.manager = manager;
    }

    /** pi {@code getHttpIdleTimeoutMs}（settings-manager.ts:900-902）。 */
    public long getHttpIdleTimeoutMs() {
        return TimeoutSettings.parseTimeoutSetting(
            manager.effective().httpIdleTimeoutMs, "httpIdleTimeoutMs")
            .orElse(TimeoutSettings.DEFAULT_HTTP_IDLE_TIMEOUT_MS);
    }

    /** pi {@code setHttpIdleTimeoutMs}（:904-912）：非法抛逐字；java 惯例 markModified。 */
    public void setHttpIdleTimeoutMs(long timeoutMs) {
        if (timeoutMs < 0) {
            throw new IllegalArgumentException(
                "Invalid httpIdleTimeoutMs setting: " + timeoutMs);
        }
        manager.global().httpIdleTimeoutMs = timeoutMs;
        manager.markModified("httpIdleTimeoutMs");
    }

    /** pi {@code getWebSocketConnectTimeoutMs}（:935-937）：缺席 ⇒ 空（无默认）。 */
    public java.util.OptionalLong getWebSocketConnectTimeoutMs() {
        return TimeoutSettings.parseTimeoutSetting(
            manager.effective().websocketConnectTimeoutMs, "websocketConnectTimeoutMs");
    }

    /** pi {@code CACHE_WARMING_MODES}（settings-manager.ts:52）。 */
    private static final java.util.Set<String> CACHE_WARMING_MODES =
        java.util.Set.of("off", "streaming", "idle");

    /**
     * pi {@code getCacheWarmingMode}（:914-918）：<b>只读全局域</b>（pi 注释：
     * warming costs money——项目域被忽略）；值 ∉ {off,streaming,idle} ⇒ 回落
     * {@code "streaming"}（settings-defaults.ts:65）。
     */
    public String getCacheWarmingMode() {
        var mode = manager.global().cacheWarming;
        return mode != null && CACHE_WARMING_MODES.contains(mode) ? mode : "streaming";
    }

    /** pi {@code setCacheWarmingMode}（:920-923）：写全局域＋markModified。 */
    public void setCacheWarmingMode(String mode) {
        manager.global().cacheWarming = mode;
        manager.markModified("cacheWarming");
    }
}
