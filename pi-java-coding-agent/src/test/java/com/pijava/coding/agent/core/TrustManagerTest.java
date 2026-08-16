package com.pijava.coding.agent.core;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** Trust decisions persist across TrustManager instances (Phase 4 §12). */
class TrustManagerTest {

    @Test
    void trustAndUntrustPersistToDisk() throws Exception {
        Path root = Files.createTempDirectory("pi-trust");
        Path project = root.resolve("project");

        var manager = new TrustManager(root, "ask");
        assertThat(manager.isTrusted(project)).isFalse();
        manager.trust(project, true);
        assertThat(manager.isTrusted(project)).isTrue();

        // A fresh instance reads the same marker.
        var reloaded = new TrustManager(root, "ask");
        assertThat(reloaded.isTrusted(project)).isTrue();

        reloaded.trust(project, false);
        var afterUntrust = new TrustManager(root, "ask");
        assertThat(afterUntrust.isTrusted(project)).isFalse();
    }

    @Test
    void defaultTrustAppliesWhenNoMarker() throws Exception {
        Path root = Files.createTempDirectory("pi-trust-default");
        Path project = root.resolve("project");
        assertThat(new TrustManager(root, "always").isTrusted(project)).isTrue();
        assertThat(new TrustManager(root, "never").isTrusted(project)).isFalse();
    }
}