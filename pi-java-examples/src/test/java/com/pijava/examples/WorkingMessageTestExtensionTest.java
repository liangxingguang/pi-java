package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.pijava.coding.agent.extension.WorkingIndicatorOptions;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;

import org.junit.jupiter.api.Test;

/** docs/27 W5：working-message-test 验收——自定义文案与单帧指示器逐字。 */
class WorkingMessageTestExtensionTest {

    @Test
    void constantsArePiLiterals() {
        assertThat(WorkingMessageTestExtension.CUSTOM_MESSAGE)
            .isEqualTo("\u001b[38;2;155;86;63mWorking... (custom)\u001b[39m");
        assertThat(WorkingMessageTestExtension.CUSTOM_INDICATOR.frames())
            .isEqualTo(List.of("\u001b[38;2;155;86;63m●\u001b[39m"));
        assertThat(WorkingMessageTestExtension.CUSTOM_INDICATOR.intervalMs()).isNull();
    }

    @Test
    void sessionStartAppliesMessageAndIndicator() {
        var w = new W3Context();
        new WorkingMessageTestExtension().register(w.ctx);

        w.fire(ExtensionEventSpec.SESSION_START, null);

        assertThat(w.ui.workingMessages())
            .containsExactly(WorkingMessageTestExtension.CUSTOM_MESSAGE);
        var applied = w.ui.workingIndicators();
        assertThat(applied).hasSize(1);
        assertThat(applied.get(0)).isEqualTo(new WorkingIndicatorOptions(
            List.of("\u001b[38;2;155;86;63m●\u001b[39m"), null));
    }
}
