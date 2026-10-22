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
}
