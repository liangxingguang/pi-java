package com.pijava.tui.screen;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/62（L7 W2）：模型行文案——去重别名 {@code (name)}（pi
 * {@code model-selector.ts:326-334}）。
 */
class ModelSelectorScreenTest {

    @Test
    void aliasIsDroppedWhenTheIdAlreadyCoversTheName() {
        assertThat(ModelSelectorScreen.label("claude-sonnet-4-5", "anthropic", "Claude Sonnet 4.5"))
            .isEqualTo("anthropic/claude-sonnet-4-5");
    }

    @Test
    void aliasIsShownWhenTheNameAddsInformation() {
        assertThat(ModelSelectorScreen.label("mistral-small-2603", "mistral", "Mistral Small 4"))
            .isEqualTo("mistral/mistral-small-2603 (Mistral Small 4)");
    }
}
