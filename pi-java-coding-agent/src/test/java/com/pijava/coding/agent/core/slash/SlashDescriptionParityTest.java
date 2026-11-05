package com.pijava.coding.agent.core.slash;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** L5 W10：pi slash-commands.ts:26-41 的描述逐字（/import /share /resume）。 */
class SlashDescriptionParityTest {

    @Test
    void changedDescriptionsMatchPi() {
        var registry = CommandRegistry.withBuiltins();
        assertThat(registry.get("import").description())
            .isEqualTo("Import a JSONL session, preserving its ID and working directory");
        assertThat(registry.get("share").description())
            .isEqualTo("Share session via a Radius artifact or a GitHub gist");
        assertThat(registry.get("resume").description())
            .isEqualTo("Resume another session");
    }
}
