package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import com.pijava.ai.model.ModelId;
import com.pijava.coding.agent.extension.NotifyType;
import com.pijava.coding.agent.extension.event.ExtensionEvent;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;

import org.junit.jupiter.api.Test;

/** docs/27 W5：model-status 验收——model_select 的通知与状态段、restore 静默臂。 */
class ModelStatusExtensionTest {

    private static final ModelId<?> CLAUDE = ModelId.of("anthropic", "claude-3");
    private static final ModelId<?> GPT = ModelId.of("openai", "gpt-5");

    @Test
    void labels() {
        assertThat(ModelStatusExtension.nextLabel(CLAUDE)).isEqualTo("anthropic/claude-3");
        assertThat(ModelStatusExtension.previousLabel(null)).isEqualTo("none");
        assertThat(ModelStatusExtension.previousLabel(GPT)).isEqualTo("openai/gpt-5");
        assertThat(ModelStatusExtension.statusText(CLAUDE)).isEqualTo("🤖 claude-3");
    }

    @Test
    void modelSelectNotifiesAndWritesStatus() {
        var w = new W3Context();
        new ModelStatusExtension().register(w.ctx);

        w.fire(ExtensionEventSpec.MODEL_SELECT,
            new ExtensionEvent.ModelSelectEvent(CLAUDE, GPT, "cycle"));

        assertThat(w.ui.notices()).hasSize(1);
        assertThat(w.ui.notices().get(0).message()).isEqualTo("Model: anthropic/claude-3");
        assertThat(w.ui.notices().get(0).type()).isEqualTo(NotifyType.INFO);
        assertThat(w.ui.statuses()).containsEntry("model", "🤖 claude-3");
    }

    @Test
    void restoreSourceSkipsNotification() {
        var w = new W3Context();
        new ModelStatusExtension().register(w.ctx);

        w.fire(ExtensionEventSpec.MODEL_SELECT,
            new ExtensionEvent.ModelSelectEvent(CLAUDE, null, "restore"));

        assertThat(w.ui.notices()).isEmpty();
        assertThat(w.ui.statuses()).containsEntry("model", "🤖 claude-3");
    }
}
