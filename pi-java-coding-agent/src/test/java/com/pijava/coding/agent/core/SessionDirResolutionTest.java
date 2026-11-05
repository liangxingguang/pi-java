package com.pijava.coding.agent.core;

import java.nio.file.Path;

import com.pijava.coding.agent.cli.ArgsParser;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B278（docs/24 W4c）：pi {@code normalizePath} 的 {@code ~} 展开与显式目录判定。
 */
class SessionDirResolutionTest {

    private static AgentSessionFactory.ResolvedSessionDir resolve(String... args) {
        var parsed = ArgsParser.parse(args);
        return AgentSessionFactory.resolveSessionDir(parsed,
            SettingsManager.load(parsed.projectTrustOverride()).effective());
    }

    @Test
    void tildeExpandsToTheUserHome() {
        var dir = resolve("--session-dir", "~");
        assertThat(dir.flat()).isTrue();
        assertThat(dir.path()).isEqualTo(Path.of(System.getProperty("user.home")));
    }

    @Test
    void tildeSubpathExpands() {
        var dir = resolve("--session-dir", "~/sessions");
        assertThat(dir.flat()).isTrue();
        assertThat(dir.path()).isEqualTo(
            Path.of(System.getProperty("user.home")).resolve("sessions"));
    }

    @Test
    void explicitRelativeDirectoryStaysAsGivenAndFlat() {
        var dir = resolve("--session-dir", "docs/sessions");
        assertThat(dir.flat()).isTrue();
        assertThat(dir.path()).isEqualTo(Path.of("docs/sessions"));
    }
}
