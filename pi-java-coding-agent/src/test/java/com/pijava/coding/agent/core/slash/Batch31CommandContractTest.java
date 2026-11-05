package com.pijava.coding.agent.core.slash;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Batch31CommandContractTest {

    @Test
    void descriptionsMatchPi() {
        var registry = CommandRegistry.withBuiltins();

        assertThat(registry.get("settings").description()).isEqualTo("Open settings menu");
        assertThat(registry.get("scoped-models").description())
            .isEqualTo("Enable/disable models for Ctrl+P cycling");
        assertThat(registry.get("share").description())
            .isEqualTo("Share session via a Radius artifact or a GitHub gist");
        assertThat(registry.get("login").description())
            .isEqualTo("Configure provider authentication");
    }

    @Test
    void interactiveCommandsReturnTheirUiMarkers() {
        var registry = CommandRegistry.withBuiltins();

        assertThat(registry.get("settings").execute("", null).toCompletableFuture().join())
            .isEqualTo(CommandRegistry.UI_SETTINGS);
        assertThat(registry.get("share").execute("", null).toCompletableFuture().join())
            .isEqualTo(CommandRegistry.UI_SHARE);
        assertThat(registry.get("login").execute("", null).toCompletableFuture().join())
            .isEqualTo(CommandRegistry.UI_LOGIN);
    }
}
