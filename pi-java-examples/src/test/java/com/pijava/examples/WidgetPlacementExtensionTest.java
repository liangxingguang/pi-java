package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import com.pijava.coding.agent.extension.ExtensionWidgetOptions;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;

import org.junit.jupiter.api.Test;

/** docs/27 W5：widget-placement 验收——两区键位、文本行与 placement。 */
class WidgetPlacementExtensionTest {

    @Test
    void sessionStartWritesBothZones() {
        var w = new W3Context();
        new WidgetPlacementExtension().register(w.ctx);

        w.fire(ExtensionEventSpec.SESSION_START, null);

        assertThat(w.ui.widgets()).containsEntry("widget-above",
            java.util.List.of("Above editor widget"));
        assertThat(w.ui.widgets()).containsEntry("widget-below",
            java.util.List.of("Below editor widget"));
        assertThat(w.ui.widgetPlacements()).containsEntry("widget-above", null);
        assertThat(w.ui.widgetPlacements())
            .containsEntry("widget-below", ExtensionWidgetOptions.BELOW_EDITOR);
    }

    @Test
    void noUiSessionWritesNothing() {
        var w = new W3Context();
        w.mode = "print";
        new WidgetPlacementExtension().register(w.ctx);

        w.fire(ExtensionEventSpec.SESSION_START, null);

        assertThat(w.ui.widgets()).isEmpty();
    }
}
