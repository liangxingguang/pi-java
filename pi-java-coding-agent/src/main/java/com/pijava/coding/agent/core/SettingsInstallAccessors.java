package com.pijava.coding.agent.core;

/**
 * 安装/分析面设置访问器（批 11 裁决⑧的 {@link SettingsAccessors} 拆分）：
 * {@code deviceId}/{@code lastChangelogVersion}/{@code collapseChangelog}/
 * {@code enableInstallTelemetry}/{@code enableAnalytics}/{@code trackingId}。
 */
public final class SettingsInstallAccessors {

    private final SettingsManager manager;

    SettingsInstallAccessors(SettingsManager manager) {
        this.manager = manager;
    }

    /**
     * pi {@code getOrCreateDeviceId}（settings-manager.ts:1066-1074）：只读写全局域
     * （pi 注释：项目域忽略，防 clone 同 ID）；空则生成 UUID 落盘（即时 flush——
     * 崩溃前必须持久，pi 的 save 语义承重，裁决⑥），幂等。
     */
    public String getOrCreateDeviceId() {
        var global = manager.global();
        if (global.deviceId == null || global.deviceId.isEmpty()) {
            global.deviceId = java.util.UUID.randomUUID().toString();
            manager.markModified("deviceId");
            manager.flush();
        }
        return global.deviceId;
    }

    /** pi {@code getLastChangelogVersion}（settings-manager.ts:678-681）：直读。 */
    public String getLastChangelogVersion() {
        return manager.effective().lastChangelogVersion;
    }

    /** pi {@code setLastChangelogVersion}（:683-685）：写全局＋markModified。 */
    public void setLastChangelogVersion(String version) {
        manager.global().lastChangelogVersion = version;
        manager.markModified("lastChangelogVersion");
    }

    /** pi {@code getCollapseChangelog}（:1023-1025）：{@code ?? false}。 */
    public boolean getCollapseChangelog() {
        return Boolean.TRUE.equals(manager.effective().collapseChangelog);
    }

    /** pi {@code setCollapseChangelog}（:1027-1031）。 */
    public void setCollapseChangelog(boolean collapse) {
        manager.global().collapseChangelog = collapse;
        manager.markModified("collapseChangelog");
    }

    /** pi {@code getEnableInstallTelemetry}（:1033-1035）：{@code ?? true}。 */
    public boolean getEnableInstallTelemetry() {
        var value = manager.effective().enableInstallTelemetry;
        return value == null || value;
    }

    /** pi {@code setEnableInstallTelemetry}（:1037-1041）。 */
    public void setEnableInstallTelemetry(boolean enabled) {
        manager.global().enableInstallTelemetry = enabled;
        manager.markModified("enableInstallTelemetry");
    }

    /** pi {@code getEnableAnalytics}（:1043-1045）：{@code ?? false}。 */
    public boolean getEnableAnalytics() {
        return Boolean.TRUE.equals(manager.effective().enableAnalytics);
    }

    /** pi {@code getTrackingId}（:1047-1049）：直读（可 null）。 */
    public String getTrackingId() {
        return manager.effective().trackingId;
    }

    /**
     * pi {@code setEnableAnalytics}（:1051-1062）：opt-in 且无 trackingId 时生成
     * {@code randomUUID()}；两键 markModified；<b>即时 flush</b>（id 生成点，
     * pi 的 save 语义承重，裁决⑥）。
     */
    public void setEnableAnalytics(boolean enabled) {
        var global = manager.global();
        global.enableAnalytics = enabled;
        manager.markModified("enableAnalytics");
        if (enabled && (global.trackingId == null || global.trackingId.isEmpty())) {
            global.trackingId = java.util.UUID.randomUUID().toString();
            manager.markModified("trackingId");
            manager.flush();
        }
    }
}
