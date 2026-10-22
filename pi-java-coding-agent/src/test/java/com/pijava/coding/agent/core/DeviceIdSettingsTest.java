package com.pijava.coding.agent.core;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 11 T5（docs/22 §3.9，pi {@code getOrCreateDeviceId}，
 * settings-manager.ts:1066-1074）：稳定安装 UUID——只读写<b>全局域</b>
 * （pi 注释：项目域忽略，防 clone 同 ID）；空则生成并<b>即时落盘</b>
 * （pi save 语义承重，裁决⑥），幂等。
 */
class DeviceIdSettingsTest {

    private static FileSettingsStorage storage(Path dir) {
        return new FileSettingsStorage(dir, dir);
    }

    /** 首次生成 ⇒ 即时落盘（不等会话关闭）；重读得见；再次调用同值（幂等）。 */
    @Test
    void generatesPersistsImmediatelyAndIsIdempotent(@TempDir Path dir) {
        var manager = SettingsManager.withStorage(storage(dir));

        var first = manager.install().getOrCreateDeviceId();

        assertThat(first).isNotBlank();
        // 即时 flush：不经 manager.flush()，新 manager 重读已见。
        var reloaded = SettingsManager.withStorage(storage(dir));
        assertThat(reloaded.install().getOrCreateDeviceId()).isEqualTo(first);
        assertThat(manager.install().getOrCreateDeviceId()).isEqualTo(first);
    }

    /** 项目域有 deviceId 时仍读写全局域（pi 的项目域忽略语义）。 */
    @Test
    void projectScopeDeviceIdIsIgnored(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve(".pi-java"));
        Files.writeString(dir.resolve(".pi-java").resolve("settings.json"),
            "{\"deviceId\":\"project-clone-id\"}");

        var manager = SettingsManager.withStorage(storage(dir));
        var generated = manager.install().getOrCreateDeviceId();

        assertThat(generated).isNotEqualTo("project-clone-id");
        // 写在全球域文件；项目域原值不动。
        var globalJson = Files.readString(dir.resolve("settings.json"));
        assertThat(globalJson).contains(generated);
        assertThat(Files.readString(dir.resolve(".pi-java").resolve("settings.json")))
            .contains("project-clone-id");
    }
}
