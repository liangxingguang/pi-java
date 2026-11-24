package com.pijava.tui.screen.settings;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.pijava.tui.util.ElementRenderSupport;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.tui.event.MouseButton;
import dev.tamboui.tui.event.MouseEvent;

/** L7 W6（C3）：设置列表对齐/截断/描述/滚动/鼠标。 */
class SettingsListElementTest {

    private static SettingsListModel model(int maxVisible) {
        return new SettingsListModel(List.of(
            SettingsItem.cycle("a", "alpha", "first setting", "on", List.of("on", "off")),
            SettingsItem.cycle("b", "very-long-label", "second setting description",
                "value", List.of("value", "other"))), maxVisible);
    }

    private static String row(Buffer buffer, int y) {
        var sb = new StringBuilder();
        for (int x = 0; x < buffer.width(); x++) {
            sb.append(buffer.get(x, y).symbol());
        }
        return sb.toString();
    }

    @Test
    void alignsLabelsAndMarksSelection() throws Exception {
        var model = model(10);
        var element = new SettingsListElement(model, null);
        var buffer = ElementRenderSupport.render(element, 40, 12);
        assertThat(row(buffer, 0)).startsWith("> ");
        assertThat(row(buffer, 2)).startsWith("→ alpha");
        assertThat(row(buffer, 2).substring(2, 17)).isEqualTo("alpha          ");
        assertThat(row(buffer, 3)).startsWith("  very-long-label");
        model.move(1);
        buffer = ElementRenderSupport.render(element, 40, 12);
        assertThat(row(buffer, 3).startsWith("→ very-long-label  ")).isTrue();
    }

    @Test
    void showsScrollIndicatorAndWrappedDescription() throws Exception {
        var model = model(1);
        model.move(1);
        var buffer = ElementRenderSupport.render(
            new SettingsListElement(model, null), 24, 12);
        assertThat(row(buffer, 2).startsWith("→ very-long-label")).isTrue();
        assertThat(row(buffer, 3)).contains("(2/2)");
        assertThat(buffer.height()).isGreaterThan(3);
    }

    @Test
    void showsEmptyMessagesForBothModes() throws Exception {
        var empty = new SettingsListModel(List.of(), 10);
        var buffer = ElementRenderSupport.render(
            new SettingsListElement(empty, null), 30, 8);
        assertThat(row(buffer, 2)).contains("No settings available");

        var model = model(10);
        model.search("zzz");
        buffer = ElementRenderSupport.render(new SettingsListElement(model, null), 30, 8);
        assertThat(row(buffer, 2)).contains("No matching settings");
        assertThat(row(buffer, 4)).contains("Type to search");
    }

    @Test
    void mouseWheelMovesSelection() throws Exception {
        var model = model(10);
        var element = new SettingsListElement(model, null);
        ElementRenderSupport.render(element, 40, 12);
        element.handleMouseEvent(MouseEvent.scrollDown(1, 3));
        assertThat(model.selectedIndex()).isEqualTo(1);
        element.handleMouseEvent(MouseEvent.scrollUp(1, 3));
        assertThat(model.selectedIndex()).isZero();
    }

    @Test
    void mouseClickSelectsThenActivates() throws Exception {
        var model = model(10);
        var activations = new AtomicInteger();
        var element = new SettingsListElement(model, activations::incrementAndGet);
        ElementRenderSupport.render(element, 40, 12);
        element.handleMouseEvent(MouseEvent.press(MouseButton.LEFT, 3, 3));
        assertThat(model.selectedIndex()).isEqualTo(1);
        assertThat(activations.get()).isZero();
        element.handleMouseEvent(MouseEvent.press(MouseButton.LEFT, 3, 3));
        assertThat(activations.get()).isEqualTo(1);
    }
}
