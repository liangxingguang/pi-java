package com.pijava.coding.agent.core;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 11 T7（docs/22 §3.13/§3.14）：声明面 14 键的字段 JSON 往返（写→读→写不丢、
 * 不再落 {@code unknown}）与逐键 quirk——cacheWarming 非法回落 streaming＋只读
 * 全局域、fullscreenWheelScrollLines clamp 1–100/"auto"、fullscreenExitOutput 非法
 * 回落 transcript、fullscreenScrollbar 非法回落 auto、showHardwareCursor env 回落、
 * {@code setEnableAnalytics} 生成 trackingId＋幂等、warnings 默认空形。
 */
class DeclaredSettingsAccessorsTest {

    private static FileSettingsStorage storage(Path dir) {
        return new FileSettingsStorage(dir, dir);
    }

    private static final String ALL_KEYS = """
        {
          "lastChangelogVersion": "1.2.3",
          "showCacheMissNotices": true,
          "collapseChangelog": true,
          "enableInstallTelemetry": false,
          "enableAnalytics": true,
          "trackingId": "tid-1",
          "deviceId": "did-1",
          "warnings": {"anthropicExtraUsage": false},
          "cacheWarming": "idle",
          "httpIdleTimeoutMs": 45000,
          "websocketConnectTimeoutMs": "disabled",
          "showHardwareCursor": true,
          "fullscreenExitOutput": "resume-hint",
          "fullscreenScrollbar": "hidden",
          "fullscreenCopyOnSelect": false,
          "fullscreenWheelScrollLines": 25
        }
        """;

    /** 全键写→读→写不丢；具名字段解析后不再落 unknown 兜底。 */
    @Test
    void declaredKeysRoundTripWithoutLoss(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("settings.json"), ALL_KEYS);

        var manager = SettingsManager.withStorage(storage(dir));
        var effective = manager.effective();
        assertThat(effective.lastChangelogVersion).isEqualTo("1.2.3");
        assertThat(effective.warnings.anthropicExtraUsage()).isFalse();
        assertThat(effective.fullscreenWheelScrollLines).isEqualTo(25);
        assertThat(effective.unknown()).isEmpty();

        // 触发一次 flush（任一 setter），重读全键仍在。
        manager.install().setCollapseChangelog(true);
        manager.flush();

        var reloaded = SettingsManager.withStorage(storage(dir));
        assertThat(reloaded.effective().lastChangelogVersion).isEqualTo("1.2.3");
        assertThat(reloaded.effective().trackingId).isEqualTo("tid-1");
        assertThat(reloaded.install().getOrCreateDeviceId()).isEqualTo("did-1");
        assertThat(reloaded.network().getCacheWarmingMode()).isEqualTo("idle");
        assertThat(reloaded.uiPrefs().getFullscreenExitOutput()).isEqualTo("resume-hint");
        assertThat(reloaded.uiPrefs().getFullscreenScrollbar()).isEqualTo("hidden");
        assertThat(reloaded.uiPrefs().getFullscreenWheelScrollLines()).isEqualTo(25);
        assertThat(reloaded.network().getWebSocketConnectTimeoutMs()).hasValue(0);
    }

    /** 默认面：pi SETTINGS_DEFAULTS 逐键（settings-defaults.ts:27-71）。 */
    @Test
    void defaultsMatchPi(@TempDir Path dir) {
        var manager = SettingsManager.withStorage(storage(dir));

        assertThat(manager.uiPrefs().getShowCacheMissNotices()).isFalse();
        assertThat(manager.install().getCollapseChangelog()).isFalse();
        assertThat(manager.install().getEnableInstallTelemetry()).isTrue();
        assertThat(manager.install().getEnableAnalytics()).isFalse();
        assertThat(manager.install().getTrackingId()).isNull();
        assertThat(manager.network().getCacheWarmingMode()).isEqualTo("streaming");
        assertThat(manager.uiPrefs().getFullscreenExitOutput()).isEqualTo("transcript");
        assertThat(manager.uiPrefs().getFullscreenScrollbar()).isEqualTo("auto");
        assertThat(manager.uiPrefs().getFullscreenCopyOnSelect()).isTrue();
        assertThat(manager.uiPrefs().getFullscreenWheelScrollLines()).isEqualTo("auto");
        // warnings 默认空形（pi 浅拷贝 ?? {} ⇒ 组件 null）。
        assertThat(manager.uiPrefs().getWarnings().anthropicExtraUsage()).isNull();
    }

    /** cacheWarming：非法值回落 streaming；<b>只读全局域</b>（项目域被忽略）。 */
    @Test
    void cacheWarmingFallsBackAndReadsGlobalOnly(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("settings.json"), "{\"cacheWarming\":\"bogus\"}");
        assertThat(SettingsManager.withStorage(storage(dir)).network()
            .getCacheWarmingMode()).isEqualTo("streaming");

        // 项目域设 idle、全局缺席 ⇒ 仍回落 streaming（pi：Global only）。
        Files.writeString(dir.resolve("settings.json"), "{}");
        Files.createDirectories(dir.resolve(".pi-java"));
        Files.writeString(dir.resolve(".pi-java").resolve("settings.json"),
            "{\"cacheWarming\":\"idle\"}");
        assertThat(SettingsManager.withStorage(storage(dir)).network()
            .getCacheWarmingMode()).isEqualTo("streaming");
    }

    /** fullscreenWheelScrollLines：clamp 1–100 后 floor；非 number ⇒ "auto"。 */
    @Test
    void wheelScrollLinesClampAndAuto(@TempDir Path dir) {
        var uiPrefs = SettingsManager.withStorage(storage(dir)).uiPrefs();

        uiPrefs.setFullscreenWheelScrollLines(0.5);
        assertThat(uiPrefs.getFullscreenWheelScrollLines()).isEqualTo(1);
        uiPrefs.setFullscreenWheelScrollLines(250);
        assertThat(uiPrefs.getFullscreenWheelScrollLines()).isEqualTo(100);
        uiPrefs.setFullscreenWheelScrollLines(42.9);
        assertThat(uiPrefs.getFullscreenWheelScrollLines()).isEqualTo(42);
        uiPrefs.setFullscreenWheelScrollLines("auto");
        assertThat(uiPrefs.getFullscreenWheelScrollLines()).isEqualTo("auto");
    }

    /** fullscreenExitOutput/Scrollbar：非法值吞掉回落默认（pi 逐字）。 */
    @Test
    void fullscreenEnumsSwallowInvalidValues(@TempDir Path dir) {
        var uiPrefs = SettingsManager.withStorage(storage(dir)).uiPrefs();

        uiPrefs.setFullscreenExitOutput("bogus");
        assertThat(uiPrefs.getFullscreenExitOutput()).isEqualTo("transcript");
        uiPrefs.setFullscreenExitOutput("resume-hint");
        assertThat(uiPrefs.getFullscreenExitOutput()).isEqualTo("resume-hint");

        uiPrefs.setFullscreenScrollbar("bogus");
        assertThat(uiPrefs.getFullscreenScrollbar()).isEqualTo("auto");
        uiPrefs.setFullscreenScrollbar("always");
        assertThat(uiPrefs.getFullscreenScrollbar()).isEqualTo("always");
    }

    /** showHardwareCursor：设置缺席 ⇒ env 回落（PI_HARDWARE_CURSOR === "1"）。 */
    @Test
    void hardwareCursorFallsBackToEnv(@TempDir Path dir) {
        var uiPrefs = SettingsManager.withStorage(storage(dir)).uiPrefs();

        assertThat(uiPrefs.getShowHardwareCursor())
            .isEqualTo("1".equals(System.getenv("PI_HARDWARE_CURSOR")));

        uiPrefs.setShowHardwareCursor(true);
        assertThat(uiPrefs.getShowHardwareCursor()).isTrue();
    }

    /** setEnableAnalytics：opt-in 且无 trackingId ⇒ 生成 UUID＋即时落盘；幂等。 */
    @Test
    void enableAnalyticsGeneratesTrackingIdOnce(@TempDir Path dir) {
        var manager = SettingsManager.withStorage(storage(dir));

        manager.install().setEnableAnalytics(true);

        var trackingId = manager.install().getTrackingId();
        assertThat(trackingId).isNotBlank();
        // 即时 flush：新 manager 重读已见。
        var reloaded = SettingsManager.withStorage(storage(dir));
        assertThat(reloaded.install().getEnableAnalytics()).isTrue();
        assertThat(reloaded.install().getTrackingId()).isEqualTo(trackingId);

        // 再调不再生（trackingId 已在）。
        reloaded.install().setEnableAnalytics(true);
        assertThat(reloaded.install().getTrackingId()).isEqualTo(trackingId);

        // opt-out 不动 trackingId。
        reloaded.install().setEnableAnalytics(false);
        assertThat(reloaded.install().getEnableAnalytics()).isFalse();
        assertThat(reloaded.install().getTrackingId()).isEqualTo(trackingId);
    }
}
