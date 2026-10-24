package com.pijava.coding.agent.subcommand;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 19 T4（docs/30 §7）：四 handler 输出文案逐字＋exitCode、onProgress START
 * 行输出、--models 短路在 trust 之前、self 恒不可用臂（裁决②）、信任门（D114）。
 * 全部走临时 cwd/agentDir/信任根；npm/git 网络臂不进本套件（T3 套件已覆）。
 */
class PackageCommandRunTest {

    @TempDir
    Path cwd;

    @TempDir
    Path agentDir;

    @TempDir
    Path trustRoot;

    @TempDir
    Path homeDir;

    private ByteArrayOutputStream out;
    private ByteArrayOutputStream err;
    private PrintStream originalOut;
    private PrintStream originalErr;
    private PackageCommand.CliEnv env;
    private String modelsRefreshResult;
    private final AtomicInteger modelsRefreshCalls = new AtomicInteger();

    @BeforeEach
    void setUp() {
        out = new ByteArrayOutputStream();
        err = new ByteArrayOutputStream();
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        modelsRefreshResult = null;
        env = new PackageCommand.CliEnv(cwd, agentDir, trustRoot, dir -> {
            modelsRefreshCalls.incrementAndGet();
            return modelsRefreshResult;
        });
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }

    @Test
    void installLocalDirPrintsInstalledAndProgress() throws IOException {
        Files.createDirectories(cwd.resolve("pkg"));

        var code = PackageCommand.run("install", new String[] {"./pkg"}, env);

        assertThat(code).isZero();
        assertThat(stdout()).contains("Installing ./pkg...");   // onProgress START 行
        assertThat(stdout()).contains("Installed ./pkg");
        // 写回链：user settings 落 packages（local 源归一为相对 agentDir 路径）
        var settingsJson = Files.readString(agentDir.resolve("settings.json"));
        assertThat(settingsJson).contains("packages");
    }

    @Test
    void installMissingLocalPathErrorsVerbatim() {
        var code = PackageCommand.run("install", new String[] {"./nope"}, env);

        assertThat(code).isEqualTo(1);
        assertThat(stderr()).contains("Error: Path does not exist: " + cwd.resolve("nope"));
    }

    @Test
    void installProjectLocalRequiresTrust() throws IOException {
        Files.createDirectories(cwd.resolve("pkg"));

        var code = PackageCommand.run("install", new String[] {"./pkg", "-l"}, env);

        assertThat(code).isEqualTo(1);
        assertThat(stderr()).contains(
            "Project is not trusted. Use --approve to modify local package config.");
    }

    @Test
    void installProjectLocalWithApproveWritesProjectSettings() throws IOException {
        Files.createDirectories(cwd.resolve("pkg"));

        var code = PackageCommand.run("install", new String[] {"./pkg", "-l", "-a"}, env);

        assertThat(code).isZero();
        var projectJson = Files.readString(
            cwd.resolve(".pi-java").resolve("settings.json"));
        assertThat(projectJson).contains("packages");
    }

    @Test
    void removeWithoutMatchErrorsAndExitsOne() {
        var code = PackageCommand.run("remove", new String[] {"npm:absent"}, env);

        assertThat(code).isEqualTo(1);
        assertThat(stderr()).contains("No matching package found for npm:absent");
    }

    @Test
    void removeAliasUninstallWorks() throws IOException {
        Files.createDirectories(cwd.resolve("pkg"));
        PackageCommand.run("install", new String[] {"./pkg"}, env);

        var code = PackageCommand.run("uninstall", new String[] {"./pkg"}, env);

        assertThat(code).isZero();
        assertThat(stdout()).contains("Removed ./pkg");
    }

    @Test
    void listEmptyPrintsNoPackagesInstalled() {
        var code = PackageCommand.run("list", new String[0], env);

        assertThat(code).isZero();
        assertThat(stdout()).contains("No packages installed.");
    }

    @Test
    void listGroupsUserAndProjectPackagesVerbatim() throws IOException {
        Files.writeString(agentDir.resolve("settings.json"),
            "{\"packages\":[\"npm:foo\",{\"source\":\"npm:bar\",\"extensions\":[\"+x\"]}]}");
        // project settings 里的 local 源相对 .pi-java 基目录（pi getBaseDirForScope）。
        Files.createDirectories(cwd.resolve(".pi-java").resolve("local-pkg"));
        Files.writeString(cwd.resolve(".pi-java").resolve("settings.json"),
            "{\"packages\":[\"local-pkg\"]}");
        // untrusted 时 project 域内存清空（pi 同）⇒ 信任标记落 trustRoot。
        new com.pijava.coding.agent.core.TrustManager(trustRoot, null).trust(cwd, true);

        var code = PackageCommand.run("list", new String[0], env);

        assertThat(code).isZero();
        var text = stdout();
        assertThat(text).contains("User packages:");
        // println 行尾是平台分隔符（Windows \r\n）——不断言裸 \n。
        assertThat(text).contains("  npm:foo");
        assertThat(text).contains("  npm:bar (filtered)");
        assertThat(text).contains("Project packages:");
        assertThat(text).contains("  local-pkg");
        assertThat(text).contains("    " + cwd.resolve(".pi-java").resolve("local-pkg"));
    }

    @Test
    void updateBareShowsSkippedNoteThenSelfUnavailable() {
        var code = PackageCommand.run("update", new String[0], env);

        assertThat(code).isEqualTo(1);
        assertThat(stdout()).contains("Extensions are skipped. Run pi-java update "
            + "--extensions to update extensions.");
        assertThat(stderr()).contains("error: pi-java cannot self-update this installation.");
    }

    @Test
    void updateExtensionsWithNoPackagesPrintsUpdatedPackages() {
        var code = PackageCommand.run("update", new String[] {"--extensions"}, env);

        assertThat(code).isZero();
        assertThat(stdout()).contains("Updated packages");
    }

    @Test
    void updateUnknownSourceErrorsWithDidYouMean() throws IOException {
        Files.writeString(agentDir.resolve("settings.json"),
            "{\"packages\":[\"npm:foo@1.0.0\"]}");

        var code = PackageCommand.run("update", new String[] {"--extension", "foo"}, env);

        assertThat(code).isEqualTo(1);
        assertThat(stderr()).contains(
            "Error: No matching package found for foo. Did you mean npm:foo@1.0.0?");
    }

    @Test
    void updateModelsShortCircuitsBeforeSettingsAndTrust() {
        // cwd 无任何 settings/trust 夹具仍直通（pi :940-949 序钉死）。
        modelsRefreshResult = null;

        var code = PackageCommand.run("update", new String[] {"--models"}, env);

        assertThat(code).isZero();
        assertThat(modelsRefreshCalls).hasValue(1);
        assertThat(stdout()).contains("Model catalogs refreshed");
    }

    @Test
    void updateModelsFailureAggregatesError() {
        modelsRefreshResult = "Could not refresh model catalogs: anthropic: boom";

        var code = PackageCommand.run("update", new String[] {"--models"}, env);

        assertThat(code).isEqualTo(1);
        assertThat(stderr()).contains(
            "Error: Could not refresh model catalogs: anthropic: boom");
    }

    @Test
    void helpPrintsUsageAndExitsZero() {
        var code = PackageCommand.run("install", new String[] {"-h"}, env);

        assertThat(code).isZero();
        assertThat(stdout()).contains("pi-java install <source> [-l] [--approve|--no-approve]");
        assertThat(stdout()).contains("pi-java install git:git@github.com:user/repo");
    }

    @Test
    void invalidOptionErrorsVerbatim() {
        var code = PackageCommand.run("update", new String[] {"--bogus"}, env);

        assertThat(code).isEqualTo(1);
        assertThat(stderr()).contains("Unknown option --bogus for \"update\".");
        assertThat(stderr()).contains("Use \"pi-java --help\" or \"");
    }

    @Test
    void missingSourceErrorsVerbatim() {
        var code = PackageCommand.run("install", new String[0], env);

        assertThat(code).isEqualTo(1);
        assertThat(stderr()).contains("Missing install source.");
        assertThat(stderr()).contains("Usage: pi-java install <source>");
    }
}
