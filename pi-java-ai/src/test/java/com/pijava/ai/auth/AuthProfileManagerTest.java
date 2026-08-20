package com.pijava.ai.auth;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6-18: AuthProfileManager — 每 provider 激活 profile 的持久化。
 */
class AuthProfileManagerTest {

    @TempDir
    Path tmp;

    @Test
    void setClearAndActiveRoundTrip() {
        var manager = new AuthProfileManager(tmp.resolve("profiles.json"));

        assertThat(manager.activeProfile("anthropic")).isEmpty();

        manager.setActiveProfile("anthropic", "work");
        assertThat(manager.activeProfile("anthropic")).hasValue("work");

        manager.clearActiveProfile("anthropic");
        assertThat(manager.activeProfile("anthropic")).isEmpty();
    }

    @Test
    void profilesArePerProvider() {
        var manager = new AuthProfileManager(tmp.resolve("profiles.json"));
        manager.setActiveProfile("anthropic", "work");
        manager.setActiveProfile("openai", "personal");

        assertThat(manager.activeProfile("anthropic")).hasValue("work");
        assertThat(manager.activeProfile("openai")).hasValue("personal");
        assertThat(manager.all()).containsEntry("anthropic", "work")
            .containsEntry("openai", "personal");
    }
}
