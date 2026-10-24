package com.pijava.coding.agent.subcommand;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

import com.pijava.coding.agent.core.FileSettingsStorage;
import com.pijava.coding.agent.core.SettingsManager;
import com.pijava.coding.agent.core.TrustManager;
import com.pijava.coding.agent.spi.ConfigSelectorProvider;
import com.pijava.coding.agent.spi.ScopedResolvedPaths;

/**
 * 批 19 T6（docs/30 §7）：{@code config} 的 TUI 入口壳（pi handleConfigCommand
 * :813-888 对位）——双份 resolve（global 份信任恒拒；未信任 ⇒ project 份复用
 * global 副本＋projectModeAvailable=false）、-l 信任门文案逐字、SPI 缺席报错、
 * help/unknown/unexpected 文案逐字。
 */
class ConfigCommandTuiTest {

    @TempDir
    Path agentDir;

    @TempDir
    Path cwd;

    @TempDir
    Path trustRoot;

    @TempDir
    Path homeDir;

    private ByteArrayOutputStream out;
    private ByteArrayOutputStream err;
    private PrintStream originalOut;
    private PrintStream originalErr;

    /** 捕获形 TUI provider。 */
    private record CapturingTui(AtomicReference<ScopedResolvedPaths> paths,
            AtomicReference<Boolean> projectModeAvailable,
            AtomicReference<ConfigSelectorProvider.WriteScope> writeScope)
        implements ConfigSelectorProvider {
        @Override
        public void selectConfig(ScopedResolvedPaths resolvedPaths, SettingsManager settings,
                Path cwd, Path agentDir, WriteScope scope, boolean projectMode) {
            paths.set(resolvedPaths);
            projectModeAvailable.set(projectMode);
            writeScope.set(scope);
        }
    }

    @BeforeEach
    void setUp() {
        out = new ByteArrayOutputStream();
        err = new ByteArrayOutputStream();
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    private ConfigCommand.ConfigEnv env(ConfigSelectorProvider tui) {
        var manager = SettingsManager.withStorage(new FileSettingsStorage(agentDir, cwd));
        return new ConfigCommand.ConfigEnv(manager, trustRoot, cwd, agentDir, homeDir, tui);
    }

    private void seedResources() throws IOException {
        // user 域自动发现 skill；project 域自动发现 skill（信任后参与 project 份）。
        var userSkill = agentDir.resolve("skills/user-skill/SKILL.md");
        Files.createDirectories(userSkill.getParent());
        Files.writeString(userSkill, "# user-skill");
        var projectSkill = cwd.resolve(".pi-java/skills/project-skill/SKILL.md");
        Files.createDirectories(projectSkill.getParent());
        Files.writeString(projectSkill, "# project-skill");
    }

    @Test
    void doubleResolveTrustedProjectGetsOwnCopy() throws IOException {
        seedResources();
        new TrustManager(trustRoot, null).trust(cwd, true);
        var paths = new AtomicReference<ScopedResolvedPaths>();
        var available = new AtomicReference<Boolean>();
        var scope = new AtomicReference<ConfigSelectorProvider.WriteScope>();

        var code = ConfigCommand.run(new String[0],
            env(new CapturingTui(paths, available, scope)));

        assertThat(code).isZero();
        assertThat(available.get()).isTrue();
        assertThat(scope.get()).isEqualTo(ConfigSelectorProvider.WriteScope.GLOBAL);
        // global 份：project 资源全剔除（信任恒拒）
        assertThat(paths.get().global().skills())
            .noneMatch(r -> r.path().contains("project-skill"));
        // project 份：含 project 资源
        assertThat(paths.get().project().skills())
            .anyMatch(r -> r.path().contains("project-skill"));
    }

    @Test
    void untrustedReusesGlobalCopyAndDisablesProjectMode() throws IOException {
        seedResources();
        var paths = new AtomicReference<ScopedResolvedPaths>();
        var available = new AtomicReference<Boolean>();
        var scope = new AtomicReference<ConfigSelectorProvider.WriteScope>();

        var code = ConfigCommand.run(new String[0],
            env(new CapturingTui(paths, available, scope)));

        assertThat(code).isZero();
        assertThat(available.get()).isFalse();
        assertThat(paths.get().project()).isSameAs(paths.get().global());
    }

    @Test
    void localFlagUntrustedErrorsVerbatim() {
        var paths = new AtomicReference<ScopedResolvedPaths>();
        var tui = new CapturingTui(paths, new AtomicReference<>(), new AtomicReference<>());

        var code = ConfigCommand.run(new String[] {"-l"}, env(tui));

        assertThat(code).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains(
            "Project is not trusted. Use --approve to modify local resource config.");
        assertThat(paths.get()).isNull();
    }

    @Test
    void localFlagWithApproveStartsInProjectScope() {
        var available = new AtomicReference<Boolean>();
        var scope = new AtomicReference<ConfigSelectorProvider.WriteScope>();

        var code = ConfigCommand.run(new String[] {"-l", "-a"},
            env(new CapturingTui(new AtomicReference<>(), available, scope)));

        assertThat(code).isZero();
        assertThat(scope.get()).isEqualTo(ConfigSelectorProvider.WriteScope.PROJECT);
        assertThat(available.get()).isTrue();
    }

    @Test
    void missingTuiProviderReportsCleanError() {
        var code = ConfigCommand.run(new String[0], env(null));

        assertThat(code).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8))
            .contains("error: config requires pi-java-tui on the classpath");
    }

    @Test
    void helpPrintsConfigUsage() {
        var code = ConfigCommand.run(new String[] {"--help"}, env(null));

        assertThat(code).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8))
            .contains("pi-java config [-l] [--approve|--no-approve]")
            .contains("Press Tab in the TUI");
    }

    @Test
    void unknownOptionAndUnexpectedArgumentVerbatim() {
        assertThat(ConfigCommand.run(new String[] {"--bogus"}, env(null))).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8))
            .contains("Unknown option --bogus for \"config\".");
        assertThat(err.toString(StandardCharsets.UTF_8))
            .contains("Use \"pi-java --help\" or \"pi-java config [-l] [--approve|--no-approve]\".");
    }

    @Test
    void unexpectedArgumentVerbatim() {
        assertThat(ConfigCommand.run(new String[] {"frobnicate"}, env(null))).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8))
            .contains("Unexpected argument frobnicate.");
    }
}
