package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** docs/54 §2.4：bash-spawn-hook 验收——命令/cwd/env 变换纯函数（D240）、bash 注册。 */
class BashSpawnHookExtensionTest {

    @Test
    void registration() {
        var w = new W3Context();
        new BashSpawnHookExtension().register(w.ctx);
        assertThat(w.tools.toolNames()).contains("bash");
    }

    @Test
    void spawnTransform() {
        var t = BashSpawnHookExtension.apply("echo hi", "/cwd", "/hook",
            Map.of("FOO", "bar"));
        assertThat(t.command()).isEqualTo("source ~/.profile\necho hi");
        assertThat(t.cwd()).isEqualTo("/hook");
        assertThat(t.env()).containsEntry("FOO", "bar").containsEntry("PI_SPAWN_HOOK", "1");
    }

    @Test
    void spawnTransformFallsBackCwdAndEmptyEnv() {
        var t = BashSpawnHookExtension.apply("ls", "/cwd", null, null);
        assertThat(t.cwd()).isEqualTo("/cwd");
        assertThat(t.env()).containsOnlyKeys("PI_SPAWN_HOOK");
    }
}
