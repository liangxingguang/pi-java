package com.pijava.tui.component;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.pijava.tui.util.ElementRenderSupport;

import dev.tamboui.style.Style;

/** L7 W6（C15）：动态横线。 */
class DynamicBorderTest {

    @Test
    void rendersFullWidthRuleWithLiveStyle() throws Exception {
        var style = Style.EMPTY.reversed();
        var buffer = ElementRenderSupport.render(new DynamicBorder(() -> style), 5, 1);
        var text = new StringBuilder();
        for (int x = 0; x < 5; x++) {
            text.append(buffer.get(x, 0).symbol());
        }
        assertThat(text.toString()).isEqualTo("─────");
        assertThat(buffer.get(2, 0).style().effectiveModifiers())
            .contains(dev.tamboui.style.Modifier.REVERSED);
    }
}
