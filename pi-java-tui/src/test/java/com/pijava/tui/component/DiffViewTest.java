package com.pijava.tui.component;

import dev.tamboui.style.Style;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6-26: unified diff coloring — added green, removed red, headers cyan,
 * context dim; lines preformatted and markup-escaped.
 */
class DiffViewTest {

    @Test
    void colorsAddedRemovedHeaderAndContextLines() {
        var lines = DiffView.lines("@@ -1,1 +1,1 @@\n-old\n+new\n plain");

        assertThat(lines).hasSize(4);
        assertThat(lines.get(0).style()).isEqualTo(Style.EMPTY.fg(DiffView.HEADER));
        assertThat(lines.get(1).style()).isEqualTo(Style.EMPTY.fg(DiffView.REMOVED));
        assertThat(lines.get(2).style()).isEqualTo(Style.EMPTY.fg(DiffView.ADDED));
        assertThat(lines.get(3).style()).isEqualTo(Style.EMPTY.dim());
    }

    @Test
    void fileHeadersAreCyan() {
        var lines = DiffView.lines("--- a/f.txt\n+++ b/f.txt");
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).style()).isEqualTo(Style.EMPTY.fg(DiffView.HEADER));
        assertThat(lines.get(1).style()).isEqualTo(Style.EMPTY.fg(DiffView.HEADER));
    }

    @Test
    void diffLinesArePreformattedAndEscaped() {
        var lines = DiffView.lines("-arr[0] + \\");
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).preformatted()).isTrue();
        assertThat(lines.get(0).markup()).isEqualTo("-arr[[0]] + \\\\");
    }

    @Test
    void emptyOrNullDiffYieldsNoLines() {
        assertThat(DiffView.lines("")).isEmpty();
        assertThat(DiffView.lines(null)).isEmpty();
    }
}
