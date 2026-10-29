package com.pijava.coding.agent.core;

import org.junit.jupiter.api.Test;

import static com.pijava.coding.agent.core.KeybindingsManager.KeyStroke;
import static org.assertj.core.api.Assertions.assertThat;

class ScopedModelKeybindingsTest {

    @Test
    void modelKeysAreOverlayOnlyAndMatchPiDefaults() {
        var keys = new KeybindingsManager(false);

        assertThat(keys.resolve(KeyStroke.of("s", true, false, false))).isNull();
        assertThat(keys.matches(KeybindingsManager.MODELS_SAVE,
            KeyStroke.of("s", true, false, false))).isTrue();
        assertThat(keys.matches(KeybindingsManager.MODELS_ENABLE_ALL,
            KeyStroke.of("a", true, false, false))).isTrue();
        assertThat(keys.matches(KeybindingsManager.MODELS_CLEAR_ALL,
            KeyStroke.of("x", true, false, false))).isTrue();
        assertThat(keys.matches(KeybindingsManager.MODELS_TOGGLE_PROVIDER,
            KeyStroke.of("p", true, false, false))).isTrue();
        assertThat(keys.matches(KeybindingsManager.MODELS_REORDER_UP,
            KeyStroke.of("up", false, true, false))).isTrue();
        assertThat(keys.matches(KeybindingsManager.MODELS_REORDER_DOWN,
            KeyStroke.of("down", false, true, false))).isTrue();
    }
}
