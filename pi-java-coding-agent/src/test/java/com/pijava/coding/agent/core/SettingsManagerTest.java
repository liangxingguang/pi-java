package com.pijava.coding.agent.core;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 §16: settings deep merge, per-field setters, flush, and unknown
 * field passthrough.
 */
class SettingsManagerTest {

    @Test
    void mergesGlobalAndProjectScopes() {
        var storage = new InMemorySettingsStorage();
        var global = new Settings();
        global.defaultProvider = "openai";
        global.theme = "dark";
        storage.writeGlobal(global);
        var project = new Settings();
        project.theme = "light";
        storage.writeProject(project);

        var manager = SettingsManager.withStorage(storage);

        assertThat(manager.effective().defaultProvider).isEqualTo("openai");
        assertThat(manager.effective().theme).isEqualTo("light");
    }

    @Test
    void setterWritesGlobalAndFlushes() {
        var storage = new InMemorySettingsStorage();
        var manager = SettingsManager.withStorage(storage);

        manager.accessors().setTheme("dark");
        manager.accessors().setDefaultProvider("deepseek");
        manager.flush();

        assertThat(storage.readGlobal().theme).isEqualTo("dark");
        assertThat(storage.readGlobal().defaultProvider).isEqualTo("deepseek");
        assertThat(manager.effective().theme).isEqualTo("dark");
    }

    @Test
    void unknownFieldsRoundTrip() throws Exception {
        var storage = new InMemorySettingsStorage();
        var manager = SettingsManager.withStorage(storage);
        var global = manager.effective();
        global.setUnknown("futureField", "keep-me");
        storage.writeGlobal(global);

        var reloaded = SettingsManager.withStorage(storage).effective();
        assertThat(reloaded.unknown().get("futureField")).isEqualTo("keep-me");
    }

    @Test
    void untrustedProjectClearsProjectScope() {
        var storage = new InMemorySettingsStorage();
        var project = new Settings();
        project.theme = "light";
        storage.writeProject(project);
        var manager = SettingsManager.withStorage(storage);

        manager.setProjectTrusted(false);

        assertThat(manager.effective().theme).isNull();
        assertThat(manager.isProjectTrusted()).isFalse();
    }

    @Test
    void defaultTrustOverrideWins() {
        var storage = new InMemorySettingsStorage();
        var global = new Settings();
        global.defaultProjectTrust = "never";
        storage.writeGlobal(global);
        var manager = SettingsManager.withStorage(storage);
        manager.setProjectTrusted(true);

        assertThat(manager.isProjectTrusted()).isTrue();
    }

    @Test
    void futureUnknownFieldWithNullValueLoadsRoundTripAsUnknown() throws Exception {
        // Regression (3d closeout): after 3d, flush writes keys a pre-3d binary
        // has no field for (e.g. "retry": null) — for that older reader they
        // are unknown null-valued keys, and Map.copyOf in unknown() threw NPE
        // inside migrate() on every load. Unknown nulls must load and
        // round-trip; the reproducible future-proof shape is a key no current
        // field claims.
        var raw = """
            {"theme":"dark","someFieldFromTomorrow":null}""";
        var parsed = Json.mapper().readValue(raw, Settings.class);
        assertThat(parsed.unknown()).containsKey("someFieldFromTomorrow");
        assertThat(parsed.unknown().get("someFieldFromTomorrow")).isNull();

        var storage = new InMemorySettingsStorage();
        storage.writeGlobal(parsed);
        var reloaded = SettingsManager.withStorage(storage).effective();
        assertThat(reloaded.unknown()).containsKey("someFieldFromTomorrow");
        assertThat(Json.mapper().writeValueAsString(reloaded)).contains("\"someFieldFromTomorrow\":null");
    }

    @Test
    void migratesLegacyQueueModeAndWebsockets() {
        var storage = new InMemorySettingsStorage();
        var global = new Settings();
        global.setUnknown("queueMode", "all");
        global.setUnknown("websockets", "sse");
        storage.writeGlobal(global);

        var manager = SettingsManager.withStorage(storage);

        assertThat(manager.effective().steeringMode).isEqualTo("all");
        assertThat(manager.effective().transport).isEqualTo("sse");
        assertThat(manager.effective().unknown()).doesNotContainKey("queueMode");
    }
}
