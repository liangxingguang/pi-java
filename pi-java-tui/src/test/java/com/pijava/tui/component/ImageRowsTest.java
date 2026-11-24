package com.pijava.tui.component;

import java.util.List;

import com.pijava.tui.util.TextLayout;
import org.junit.jupiter.api.Test;

import dev.tamboui.style.Style;

import static org.assertj.core.api.Assertions.assertThat;

class ImageRowsTest {

    private static LogicalLine imageLine(int columns, int rows) {
        return new LogicalLine("", 0, 0, true, Style.EMPTY,
            new ImagePlacement("QUJD", "image/png", null, columns, rows, 7));
    }

    @Test
    void imageLineExpandsToItsCellRowsWithPayloadOnFirstRow() {
        var wrapped = TextLayout.wrap(List.of(imageLine(10, 3)), 40);
        assertThat(wrapped).hasSize(3);
        assertThat(wrapped.get(0).image()).isNotNull();
        assertThat(wrapped.get(0).image().columns()).isEqualTo(10);
        assertThat(wrapped.get(1).image()).isNull();
        assertThat(wrapped.get(2).image()).isNull();
    }

    @Test
    void imageIsClampedToAvailableWidth() {
        var wrapped = TextLayout.wrap(List.of(imageLine(20, 4)), 5);
        var image = wrapped.get(0).image();
        assertThat(image.columns()).isEqualTo(5);
        assertThat(image.rows()).isEqualTo(1);
    }

    @Test
    void textLinesStillCarryNoImagePayload() {
        var wrapped = TextLayout.wrap(List.of(
            new LogicalLine("hello", 0, 0, false, Style.EMPTY)), 40);
        assertThat(wrapped).hasSize(1);
        assertThat(wrapped.get(0).image()).isNull();
    }
}
