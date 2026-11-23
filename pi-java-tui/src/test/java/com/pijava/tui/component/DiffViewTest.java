package com.pijava.tui.component;

import com.pijava.coding.agent.theme.ThemeToken;
import com.pijava.tui.theme.TuiThemeBridge;

import dev.tamboui.style.Style;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6-26 + L7 W2: pi {@code diff.ts renderDiff} 对位——行号解析、块分组、
 * 1:1 词级 intra-line diff（含缩进剥离）、未匹配行 dim、tab 替换。
 */
class DiffViewTest {

    private static final Style REMOVED = TuiThemeBridge.fg(ThemeToken.TOOL_DIFF_REMOVED);
    private static final Style ADDED = TuiThemeBridge.fg(ThemeToken.TOOL_DIFF_ADDED);
    private static final Style CONTEXT = TuiThemeBridge.fg(ThemeToken.TOOL_DIFF_CONTEXT);

    /** pi 格式（带行号）：+/-/上下文行各归其色；行号保留。 */
    @Test
    void colorsNumberedDiffLines() {
        var lines = DiffView.lines("+12 added\n-7 removed\n 10 kept");

        assertThat(lines).hasSize(3);
        assertThat(lines.get(0).markup()).isEqualTo("+12 added");
        assertThat(lines.get(0).style()).isEqualTo(ADDED);
        assertThat(lines.get(1).markup()).isEqualTo("-7 removed");
        assertThat(lines.get(1).style()).isEqualTo(REMOVED);
        assertThat(lines.get(2).markup()).isEqualTo(" 10 kept");
        assertThat(lines.get(2).style()).isEqualTo(CONTEXT);
    }

    /** 未匹配行（含 @@/--- 头）落 toolDiffContext（dim），原文照排。 */
    @Test
    void unmatchedLinesRenderDim() {
        var lines = DiffView.lines("@@ -1,1 +1,1 @@\n--- a/f.txt\n+++ b/f.txt");

        assertThat(lines).hasSize(3);
        assertThat(lines).allSatisfy(l -> assertThat(l.style()).isEqualTo(CONTEXT));
        assertThat(lines.get(0).markup()).isEqualTo("@@ -1,1 +1,1 @@");
        assertThat(lines.get(1).markup()).isEqualTo("--- a/f.txt");
        assertThat(lines.get(2).markup()).isEqualTo("+++ b/f.txt");
    }

    /** 1:1 删/加 ⇒ 词级 intra-line diff：变化段 [reversed]，公共段平文。 */
    @Test
    void singleLinePairGetsWordLevelDiff() {
        var lines = DiffView.lines("-1 old text\n+1 new text");

        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).markup()).isEqualTo("-1 " + "[reversed]old[/] text");
        assertThat(lines.get(0).style()).isEqualTo(REMOVED);
        assertThat(lines.get(1).markup()).isEqualTo("+1 " + "[reversed]new[/] text");
        assertThat(lines.get(1).style()).isEqualTo(ADDED);
    }

    /** 漏项#5：公共前导缩进先剥离，不进词 diff（变化段从内容起）。 */
    @Test
    void commonIndentIsStrippedBeforeDiffing() {
        var lines = DiffView.lines("-2     int x = 1;\n+2     long x = 1;");

        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).markup())
            .isEqualTo("-2     " + "[reversed]int[/]" + " x = 1;");
        assertThat(lines.get(1).markup())
            .isEqualTo("+2     " + "[reversed]long[/]" + " x = 1;");
    }

    /** 多行块（非 1:1）⇒ 先删后加原样，无词级。 */
    @Test
    void multiLineBlocksRenderRemovedFirstThenAdded() {
        var lines = DiffView.lines("-1 a\n-2 b\n+3 c\n+4 d");

        assertThat(lines).extracting(l -> l.markup().substring(0, 1))
            .containsExactly("-", "-", "+", "+");
        assertThat(lines).extracting(l -> l.markup())
            .containsExactly("-1 a", "-2 b", "+3 c", "+4 d");
        assertThat(lines.subList(0, 2)).allSatisfy(l -> assertThat(l.style()).isEqualTo(REMOVED));
        assertThat(lines.subList(2, 4)).allSatisfy(l -> assertThat(l.style()).isEqualTo(ADDED));
    }

    /** tab 展开三空格（含词级臂内）。 */
    @Test
    void tabsExpandToThreeSpaces() {
        var lines = DiffView.lines("-5 a\tb\n+5 a\tc");

        assertThat(lines.get(0).markup()).isEqualTo("-5 " + "a   " + "[reversed]b[/]");
        assertThat(lines.get(1).markup()).isEqualTo("+5 " + "a   " + "[reversed]c[/]");
    }

    /** 行是 preformatted 且内容经 markup 转义。 */
    @Test
    void diffLinesArePreformattedAndEscaped() {
        var lines = DiffView.lines("--- arr[0] + \\");
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).preformatted()).isTrue();
        assertThat(lines.get(0).markup()).isEqualTo("--- arr[[0]] + \\\\");
    }

    @Test
    void emptyOrNullDiffYieldsNoLines() {
        assertThat(DiffView.lines("")).isEmpty();
        assertThat(DiffView.lines(null)).isEmpty();
    }
}
