package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import com.pijava.coding.agent.extension.event.ExtensionEventSpec;

import org.junit.jupiter.api.Test;

/** docs/27 W5：hidden-thinking-label 验收——默认标签、设置/复位两臂与文案。 */
class HiddenThinkingLabelExtensionTest {

    @Test
    void sessionStartAppliesDefaultLabel() {
        var w = new W3Context();
        var ext = new HiddenThinkingLabelExtension();
        ext.register(w.ctx);

        w.fire(ExtensionEventSpec.SESSION_START, null);

        assertThat(ext.label()).isEqualTo("Pondering...");
        assertThat(w.ui.hiddenThinkingLabels()).containsExactly("Pondering...");
    }

    @Test
    void commandSetsCustomLabel() {
        var w = new W3Context();
        var ext = new HiddenThinkingLabelExtension();
        ext.register(w.ctx);

        w.command("thinking-label", "  Ruminating  ");

        assertThat(ext.label()).isEqualTo("Ruminating");
        assertThat(w.ui.hiddenThinkingLabels()).containsExactly("Ruminating");
        assertThat(w.ui.notices()).extracting(ExampleFixtures.ScriptedUi.Notice::message)
            .containsExactly("Hidden thinking label set to: Ruminating");
    }

    @Test
    void commandWithoutArgsResetsToDefault() {
        var w = new W3Context();
        var ext = new HiddenThinkingLabelExtension();
        ext.register(w.ctx);

        w.command("thinking-label", "Deep");
        w.command("thinking-label", "   ");

        assertThat(ext.label()).isEqualTo(HiddenThinkingLabelExtension.DEFAULT_LABEL);
        assertThat(w.ui.hiddenThinkingLabels()).containsExactly("Deep", null);
        assertThat(w.ui.notices()).extracting(ExampleFixtures.ScriptedUi.Notice::message)
            .containsExactly(
                "Hidden thinking label set to: Deep",
                "Hidden thinking label reset to: Pondering...");
    }

    @Test
    void commandDescriptionMatchesPi() {
        var w = new W3Context();
        new HiddenThinkingLabelExtension().register(w.ctx);

        var command = w.commands.get("thinking-label");
        assertThat(command).isNotNull();
        assertThat(command.description())
            .isEqualTo("Set the hidden thinking label. Use without args to reset.");
    }
}
