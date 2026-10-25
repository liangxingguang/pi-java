package com.pijava.coding.agent.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pijava.coding.agent.cli.ArgsParser;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SessionSetup} 的系统提示词文件发现（E4-6，批 21/docs/32 T3；
 * pi resource-loader.ts:1213-1240＋:656-671 对位）。
 */
class SessionSetupPromptFileTest {

    @TempDir
    Path cwd;

    private static SettingsManager settings(boolean trusted) {
        var manager = SettingsManager.withStorage(new InMemorySettingsStorage());
        manager.setProjectTrusted(trusted);
        return manager;
    }

    private static com.pijava.coding.agent.cli.Args args(String... argv) {
        return ArgsParser.parse(argv);
    }

    // ── SYSTEM.md 发现（信任门）────────────────────────────────

    @Test
    void projectSystemMdReplacesPreambleWhenTrusted() throws Exception {
        var dir = cwd.resolve(".pi-java");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SYSTEM.md"), "PROJECT PROMPT");

        var trusted = SessionSetup.customPromptFor(args(), settings(true), cwd);
        assertThat(trusted).isEqualTo("PROJECT PROMPT");

        var untrusted = SessionSetup.customPromptFor(args(), settings(false), cwd);
        assertThat(untrusted).isEqualTo("");   // 未信任 ⇒ 不读项目文件
    }

    @Test
    void cliFlagBeatsDiscovery() throws Exception {
        var dir = cwd.resolve(".pi-java");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SYSTEM.md"), "PROJECT PROMPT");
        var result = SessionSetup.customPromptFor(
            args("--system-prompt", "CLI PROMPT"), settings(true), cwd);
        assertThat(result).isEqualTo("CLI PROMPT");
    }

    @Test
    void cliValueThatIsAnExistingPathReadsFile() throws Exception {
        // pi resolvePromptInput 的 file-or-literal 对 --system-prompt 同样生效。
        var file = cwd.resolve("prompt.md");
        Files.writeString(file, "﻿FILE PROMPT");
        var result = SessionSetup.customPromptFor(
            args("--system-prompt", file.toString()), settings(false), cwd);
        assertThat(result).isEqualTo("FILE PROMPT");   // BOM 剥离
    }

    // ── APPEND_SYSTEM.md 发现 ──────────────────────────────────

    @Test
    void appendSystemMdOnlyWhenCliAbsent() throws Exception {
        var dir = cwd.resolve(".pi-java");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("APPEND_SYSTEM.md"), "PROJECT APPEND");

        // CLI 缺席 ⇒ 发现臂。
        assertThat(SessionSetup.appendSystemPromptFor(args(), settings(true), cwd))
            .isEqualTo("PROJECT APPEND");
        // CLI 在场 ⇒ 发现跳过（pi: appendSources 非空则不发现）。
        assertThat(SessionSetup.appendSystemPromptFor(
            args("--append-system-prompt", "CLI APPEND"), settings(true), cwd))
            .isEqualTo("CLI APPEND");
        // 未信任 ⇒ 无项目臂（全局 agentDir 臂在隔离测试环境缺席）。
        assertThat(SessionSetup.appendSystemPromptFor(args(), settings(false), cwd))
            .isEqualTo("");
    }

    @Test
    void appendCliValuesAreJoinedAndResolved() throws Exception {
        var file = cwd.resolve("extra.md");
        Files.writeString(file, "FROM FILE");
        var result = SessionSetup.appendSystemPromptFor(
            args("--append-system-prompt", "LITERAL", "--append-system-prompt",
                file.toString()),
            settings(false), cwd);
        assertThat(result).isEqualTo("LITERAL\n\nFROM FILE");
    }

    @Test
    void noDiscoveryAnywhereYieldsEmpty() {
        assertThat(SessionSetup.customPromptFor(args(), settings(true), cwd)).isEmpty();
        assertThat(SessionSetup.appendSystemPromptFor(args(), settings(true), cwd)).isEmpty();
    }
}
