package com.pijava.coding.agent.core;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * B244（docs/12 §3.1）：settings {@code enableSkillCommands} accessor ——
 * pi {@code getEnableSkillCommands}/{@code setEnableSkillCommands}
 * （settings-manager.ts:1156-1164）：读缺省回落 {@code true}、写只写全局域。
 */
class EnableSkillCommandsTest {

    @Test
    void absentFieldDefaultsToTrue() {
        var manager = SettingsManager.withStorage(new InMemorySettingsStorage());
        // 字段 null ≙ pi undefined ⇒ 回落 SETTINGS_DEFAULTS.enableSkillCommands=true。
        assertThat(manager.accessors().getEnableSkillCommands()).isTrue();
    }

    @Test
    void setFalseThenGetFalse() {
        var manager = SettingsManager.withStorage(new InMemorySettingsStorage());
        manager.accessors().setEnableSkillCommands(false);
        assertThat(manager.accessors().getEnableSkillCommands()).isFalse();
    }

    @Test
    void setterWritesGlobalScopeOnly() {
        var manager = SettingsManager.withStorage(new InMemorySettingsStorage());
        manager.accessors().setEnableSkillCommands(false);

        assertThat(manager.global().enableSkillCommands)
            .as("写全局域").isFalse();
        assertThat(manager.project().enableSkillCommands)
            .as("project 域不染").isNull();
    }

    @Test
    void explicitFalseSurvivesStorageRoundTrip() {
        var storage = new InMemorySettingsStorage();
        var manager = SettingsManager.withStorage(storage);
        manager.accessors().setEnableSkillCommands(false);
        manager.flush();

        var reloaded = SettingsManager.withStorage(storage);
        assertThat(reloaded.effective().enableSkillCommands).isFalse();
        assertThat(reloaded.accessors().getEnableSkillCommands()).isFalse();
    }
}
