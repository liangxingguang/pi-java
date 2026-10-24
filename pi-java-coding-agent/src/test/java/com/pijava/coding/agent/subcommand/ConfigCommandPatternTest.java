package com.pijava.coding.agent.subcommand;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

import com.pijava.coding.agent.core.FileSettingsStorage;
import com.pijava.coding.agent.core.PackageManagers;
import com.pijava.coding.agent.core.SettingsManager;

/**
 * 批 19 T5（docs/30 §3/§7）：config enable/disable 的 pi pattern 代数写回——
 * enable ⇒ {@code +pattern}、disable ⇒ {@code -pattern}、剥前缀去重、字段级 merge
 * 保留未知键、自动发现条目可关停（resolve 重放验证）、信任门（D114）。
 */
class ConfigCommandPatternTest {

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
    private FileSettingsStorage storage;
    private SettingsManager manager;

    @BeforeEach
    void setUp() {
        out = new ByteArrayOutputStream();
        err = new ByteArrayOutputStream();
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        storage = new FileSettingsStorage(agentDir, cwd);
        manager = SettingsManager.withStorage(storage);
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    private int run(String... args) {
        return ConfigCommand.run(args,
            new ConfigCommand.ConfigEnv(manager, trustRoot, cwd, agentDir, homeDir, null));
    }

    private String globalJson() throws IOException {
        return Files.readString(agentDir.resolve("settings.json"));
    }

    @Test
    void enablePushesPlusPattern() throws IOException {
        var code = run("enable", "skills", "my-skill");

        assertThat(code).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8))
            .contains("Enabled my-skill for skills (global)");
        assertThat(manager.global().skills).containsExactly("+my-skill");
        assertThat(globalJson()).contains("+my-skill");
    }

    @Test
    void disablePushesMinusPattern() throws IOException {
        var code = run("disable", "extensions", "my-ext");

        assertThat(code).isZero();
        assertThat(manager.global().extensions).containsExactly("-my-ext");
    }

    @Test
    void toggleStripsPrefixedDuplicates() throws IOException {
        // pi :549-554：裸串/+/- 旧条目一律剥除再推新条目。
        Files.writeString(agentDir.resolve("settings.json"),
            "{\"skills\":[\"my-skill\",\"+other\",\"-my-skill\"]}");
        manager.reload();

        run("enable", "skills", "my-skill");

        assertThat(manager.global().skills).containsExactly("+other", "+my-skill");
    }

    @Test
    void writeBackPreservesUnknownKeys() throws IOException {
        Files.writeString(agentDir.resolve("settings.json"),
            "{\"futureKey\":{\"x\":1},\"theme\":\"dark\"}");
        manager.reload();

        run("disable", "themes", "old-theme");

        var json = globalJson();
        assertThat(json).contains("\"futureKey\"").contains("\"theme\" : \"dark\"")
            .contains("-old-theme");
    }

    @Test
    void autoDiscoveredEntryCanBeDisabled() throws IOException {
        // §2-5 语义差修真：自动发现条目（不在 settings 里）disable 后 resolve 重放关停。
        var skillFile = agentDir.resolve("skills").resolve("foo").resolve("SKILL.md");
        Files.createDirectories(skillFile.getParent());
        Files.writeString(skillFile, "# foo");
        var pm = PackageManagers.create(cwd, agentDir, manager, homeDir);
        var before = pm.resolve(null).skills();
        assertThat(before).anyMatch(r -> r.enabled() && r.path().endsWith("SKILL.md"));

        var code = run("disable", "skills", "skills/foo/SKILL.md");
        assertThat(code).isZero();

        manager.reload();
        var after = PackageManagers.create(cwd, agentDir, manager, homeDir)
            .resolve(null).skills();
        assertThat(after).noneMatch(r -> r.enabled() && r.path().contains("foo"));
    }

    @Test
    void projectScopeRequiresTrust() {
        var code = run("enable", "skills", "x", "-l");

        assertThat(code).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains(
            "Project is not trusted. Use --approve to modify local resource config.");
    }

    @Test
    void projectScopeWithApproveWritesProjectFile() throws IOException {
        var code = run("enable", "prompts", "review", "-l", "-a");

        assertThat(code).isZero();
        var json = Files.readString(cwd.resolve(".pi-java").resolve("settings.json"));
        assertThat(json).contains("+review");
        assertThat(manager.project().prompts).containsExactly("+review");
    }

    @Test
    void projectScopeWithSavedTrustMarkerWrites() throws IOException {
        new com.pijava.coding.agent.core.TrustManager(trustRoot, null).trust(cwd, true);

        var code = run("disable", "themes", "t", "-l");

        assertThat(code).isZero();
        assertThat(manager.project().themes).containsExactly("-t");
    }
}
