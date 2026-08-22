package com.pijava.agent.tool.builtin;

import java.util.List;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Edit-diff semantics (pi {@code edit-diff.ts}): exact → fuzzy matching,
 * reverse-order application, uniqueness/overlap validation, line endings + BOM.
 */
class EditDiffTest {

    @Test
    void exactMatchReplacesSingleBlock() {
        var result = EditDiff.applyEditsToNormalizedContent("Hello World",
            List.of(new EditDiff.Edit("World", "Java")), "f.txt");
        assertThat(result.newContent()).isEqualTo("Hello Java");
        assertThat(result.baseContent()).isEqualTo("Hello World");
    }

    @Test
    void fuzzyMatchTrimsOldTextTrailingWhitespace() {
        // "abc" is an exact substring of "abc  " so that case is exact; a
        // genuinely fuzzy match is oldText with trailing whitespace vs a clean line.
        var result = EditDiff.applyEditsToNormalizedContent("abc\ndef",
            List.of(new EditDiff.Edit("abc  ", "xyz")), "f.txt");
        assertThat(result.newContent()).isEqualTo("xyz\ndef");
    }

    @Test
    void fuzzyMatchNormalizesSmartQuotes() {
        var result = EditDiff.applyEditsToNormalizedContent("say “hello”",
            List.of(new EditDiff.Edit("say \"hello\"", "say hi")), "f.txt");
        assertThat(result.newContent()).isEqualTo("say hi");
    }

    @Test
    void fuzzyMatchNormalizesUnicodeDash() {
        var result = EditDiff.applyEditsToNormalizedContent("a — b",
            List.of(new EditDiff.Edit("a - b", "a-b")), "f.txt");
        assertThat(result.newContent()).isEqualTo("a-b");
    }

    @Test
    void multipleEditsAllMatchAgainstOriginal() {
        var result = EditDiff.applyEditsToNormalizedContent("a\nb\nc",
            List.of(new EditDiff.Edit("a", "A"), new EditDiff.Edit("c", "C")), "f.txt");
        assertThat(result.newContent()).isEqualTo("A\nb\nC");
    }

    @Test
    void overlappingEditsAreRejected() {
        assertThatThrownBy(() -> EditDiff.applyEditsToNormalizedContent("abc",
            List.of(new EditDiff.Edit("ab", "AB"), new EditDiff.Edit("bc", "BC")), "f.txt"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("overlap");
    }

    @Test
    void duplicateOldTextIsRejected() {
        assertThatThrownBy(() -> EditDiff.applyEditsToNormalizedContent("x y x",
            List.of(new EditDiff.Edit("x", "z")), "f.txt"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("occurrences");
    }

    @Test
    void emptyOldTextIsRejected() {
        assertThatThrownBy(() -> EditDiff.applyEditsToNormalizedContent("abc",
            List.of(new EditDiff.Edit("", "x")), "f.txt"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("oldText must not be empty");
    }

    @Test
    void noChangeIsRejected() {
        assertThatThrownBy(() -> EditDiff.applyEditsToNormalizedContent("abc",
            List.of(new EditDiff.Edit("abc", "abc")), "f.txt"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("No changes made");
    }

    @Test
    void lineEndingDetectionAndRestore() {
        assertThat(EditDiff.detectLineEnding("a\r\nb")).isEqualTo("\r\n");
        assertThat(EditDiff.detectLineEnding("a\nb")).isEqualTo("\n");
        assertThat(EditDiff.normalizeToLF("a\r\nb\r\nc")).isEqualTo("a\nb\nc");
        assertThat(EditDiff.restoreLineEndings("a\nb", "\r\n")).isEqualTo("a\r\nb");
    }

    @Test
    void bomStrippedAndRestored() {
        var bom = EditDiff.stripBom("﻿abc");
        assertThat(bom.bom()).isEqualTo("﻿");
        assertThat(bom.text()).isEqualTo("abc");
        assertThat(EditDiff.stripBom("abc").bom()).isEmpty();
    }

    @Test
    void normalizeForFuzzyMatchTrimsAndUnifies() {
        assertThat(EditDiff.normalizeForFuzzyMatch("a  \nb c")).isEqualTo("a\nb c");
        assertThat(EditDiff.normalizeForFuzzyMatch("‘q’")).isEqualTo("'q'");
    }
}
