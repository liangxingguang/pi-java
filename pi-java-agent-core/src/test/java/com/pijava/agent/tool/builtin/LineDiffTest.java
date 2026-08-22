package com.pijava.agent.tool.builtin;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Line diff rendering (pi {@code edit-diff.ts} display + unified patch).
 */
class LineDiffTest {

    @Test
    void diffPartsClassifyChangedLines() {
        var parts = LineDiff.diffParts("a\nb\nc", "a\nB\nc");
        assertThat(parts.stream().filter(LineDiff.DiffPart::removed)
                .flatMap(p -> p.lines().stream()))
            .containsExactly("b");
        assertThat(parts.stream().filter(LineDiff.DiffPart::added)
                .flatMap(p -> p.lines().stream()))
            .containsExactly("B");
        assertThat(parts.stream().filter(p -> !p.changed())
                .flatMap(p -> p.lines().stream()))
            .containsExactly("a", "c");
    }

    @Test
    void displayDiffContainsLineNumbersAndFirstChange() {
        var display = LineDiff.generateDiffString("hello\nworld\nfoo", "hello\nWORLD\nfoo", 2);
        assertThat(display.diff()).contains("-2 world").contains("+2 WORLD");
        assertThat(display.firstChangedLine()).isEqualTo(2);
    }

    @Test
    void unifiedPatchHasHeadersAndHunk() {
        var patch = LineDiff.generateUnifiedPatch("f.txt", "f.txt",
            "a\nb\nc\n", "a\nB\nc\n", 3);
        assertThat(patch).contains("--- f.txt").contains("+++ f.txt")
            .contains("@@").contains("-b").contains("+B");
    }

    @Test
    void separatedChangesProduceSeparateHunks() {
        // Two changes separated by more than 2×context lines → two hunks.
        var patch = LineDiff.generateUnifiedPatch("f", "f",
            "a\nb\nc\nd\ne\nf\ng\nh\nx", "A\nb\nc\nd\ne\nf\ng\nH\nx", 1);
        assertThat(patch).contains("@@ -1").contains("@@ -7");
    }

    @Test
    void emptyDiffHasNoParts() {
        assertThat(LineDiff.diffParts("same\nlines", "same\nlines"))
            .allSatisfy(p -> assertThat(p.changed()).isFalse());
    }
}
