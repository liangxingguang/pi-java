package com.pijava.tui.component;

import dev.tamboui.style.Style;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * L7 W1：工具卡片的耗时行——{@code null} 表 pi 的 {@code durationMs} 键省略，
 * 有值则末行追加 {@code Took X.Ys}。
 */
class ToolCallCardTest {

    @Test
    void withoutADurationTheCardHasNoTimingRow() {
        var lines = new ToolCallCard("bash", "ls", "running").lines();

        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).markup()).isEqualTo("[cyan]bash[/]  [dim]running[/]");
        assertThat(lines.get(1).preformatted()).isTrue();
    }

    @Test
    void aDurationAppendsADimTimingRow() {
        var lines = new ToolCallCard("bash", "ls", "done", 4_200L).lines();

        assertThat(lines).hasSize(3);
        var timing = lines.get(2);
        assertThat(timing.markup()).isEqualTo("[dim]Took 4.2s[/]");
        assertThat(timing.initialIndent()).isEqualTo(4);
        assertThat(timing.subsequentIndent()).isEqualTo(4);
        assertThat(timing.style()).isEqualTo(Style.EMPTY.dim());
    }

    @Test
    void argumentsAreHardTruncatedAtTwoHundredChars() {
        var longArgs = "x".repeat(250);

        var lines = new ToolCallCard("bash", longArgs, "done").lines();

        assertThat(lines.get(1).markup()).isEqualTo("x".repeat(200) + "…");
    }

    @Test
    void nullArgumentsRenderAsAnEmptyRow() {
        var lines = new ToolCallCard("bash", null, "done").lines();

        assertThat(lines.get(1).markup()).isEmpty();
    }
}
