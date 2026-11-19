package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import com.pijava.coding.agent.extension.event.ExtensionEventSpec;

import org.junit.jupiter.api.Test;

/** docs/27 W5：status-line 验收——状态键、三段文案、回合计数。 */
class StatusLineExtensionTest {

    @Test
    void statusKeyIsPiLiteral() {
        assertThat(StatusLineExtension.STATUS_KEY).isEqualTo("status-demo");
    }

    @Test
    void textsMatchPiTemplates() {
        assertThat(StatusLineExtension.readyText()).isEqualTo("Ready");
        assertThat(StatusLineExtension.runningText(1)).isEqualTo("● Turn 1...");
        assertThat(StatusLineExtension.completeText(2)).isEqualTo("✓ Turn 2 complete");
    }

    @Test
    void sessionStartTurnStartTurnEndWriteStatus() {
        var w = new W3Context();
        var ext = new StatusLineExtension();
        ext.register(w.ctx);

        w.fire(ExtensionEventSpec.SESSION_START, null);
        assertThat(w.ui.statuses()).containsEntry("status-demo", "Ready");

        w.fire(ExtensionEventSpec.TURN_START, null);
        assertThat(w.ui.statuses()).containsEntry("status-demo", "● Turn 1...");

        w.fire(ExtensionEventSpec.TURN_END, null);
        assertThat(w.ui.statuses()).containsEntry("status-demo", "✓ Turn 1 complete");

        w.fire(ExtensionEventSpec.TURN_START, null);
        assertThat(w.ui.statuses()).containsEntry("status-demo", "● Turn 2...");
        assertThat(ext.turnCount()).isEqualTo(2);
    }
}
