package com.pijava.coding.agent.core;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L5 W10（pi project-trust.ts:52）：无信任敏感资源时先读 trustStore 决策，
 * 只有无决策才回落 true。
 */
class TrustStorePrecedenceTest {

    @TempDir
    Path tmp;

    private boolean resolve(Path cwd, ProjectTrustStore store, Boolean override) {
        return ProjectTrustResolver.resolve(cwd, override, store, "ask",
            null, false, null, message -> { });
    }

    @Test
    void storedFalseWinsOverNoSensitiveResources() {
        var cwd = tmp.resolve("project");
        var store = new ProjectTrustStore(tmp.resolve("agent"));
        store.set(cwd, false);
        assertThat(resolve(cwd, store, null)).isFalse();
    }

    @Test
    void storedTrueKeepsTrust() {
        var cwd = tmp.resolve("project");
        var store = new ProjectTrustStore(tmp.resolve("agent"));
        store.set(cwd, true);
        assertThat(resolve(cwd, store, null)).isTrue();
    }

    @Test
    void noStoredDecisionFallsBackToTrue() {
        var cwd = tmp.resolve("project");
        var store = new ProjectTrustStore(tmp.resolve("agent"));
        assertThat(resolve(cwd, store, null)).isTrue();
    }

    @Test
    void explicitOverrideStillWins() {
        var cwd = tmp.resolve("project");
        var store = new ProjectTrustStore(tmp.resolve("agent"));
        store.set(cwd, true);
        assertThat(resolve(cwd, store, false)).isFalse();
    }
}
