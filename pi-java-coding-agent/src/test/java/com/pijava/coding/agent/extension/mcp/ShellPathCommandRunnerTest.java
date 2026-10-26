package com.pijava.coding.agent.extension.mcp;

import org.junit.jupiter.api.Test;

import com.pijava.coding.agent.core.InMemorySettingsStorage;
import com.pijava.coding.agent.core.SettingsManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 24（docs/35 T4，B188）：{@code !cmd} 两段式壳——shellPath 未配置恒默认壳
 * （零行为变化）、配置壳优先、壳不存在（ENOENT 族）回落默认壳。
 */
class ShellPathCommandRunnerTest {

    private static final boolean WINDOWS =
        System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");

    private static final String ECHO = WINDOWS ? "echo two-tier-ok" : "echo two-tier-ok";

    private static SettingsManager settings() {
        return SettingsManager.withStorage(new InMemorySettingsStorage());
    }

    @Test
    void unsetShellPathUsesDefaultShell() {
        var runner = ShellPathCommandRunner.twoTier(settings().accessors());
        {
            var out = runner.run(ECHO);
            assertThat(out).isNotNull();
            assertThat(out.strip()).isEqualTo("two-tier-ok");
        }
    }

    @Test
    void configuredShellRunsFirst() {
        var settings = settings();
        settings.accessors().setShellPath(WINDOWS ? "cmd.exe" : "/bin/sh");
        var runner = ShellPathCommandRunner.twoTier(settings.accessors());
        {
            var out = runner.run(ECHO);
            assertThat(out).isNotNull();
            assertThat(out.strip()).isEqualTo("two-tier-ok");
        }
    }

    @Test
    void missingShellFallsBackToDefault() {
        var settings = settings();
        settings.accessors().setShellPath("C:\\nonexistent\\pi-java-no-such-shell.exe");
        var runner = ShellPathCommandRunner.twoTier(settings.accessors());
        // ENOENT 族 ⇒ 回落默认壳仍跑出结果（pi resolve-config-value.ts 逐字语义）
        {
            var out = runner.run(ECHO);
            assertThat(out).isNotNull();
            assertThat(out.strip()).isEqualTo("two-tier-ok");
        }
    }

    @Test
    void nonZeroExitDoesNotFallBack() {
        var settings = settings();
        settings.accessors().setShellPath(WINDOWS ? "cmd.exe" : "/bin/sh");
        var runner = ShellPathCommandRunner.twoTier(settings.accessors());
        // 命令跑了但失败 ⇒ null（不回落——pi 只对壳缺席回落）
        assertThat(runner.run(WINDOWS ? "exit 3" : "exit 3")).isNull();
    }
}
