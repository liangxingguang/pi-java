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
    void normalizeToLFFoldsCrlfAndLoneCr() {
        assertThat(EditDiff.normalizeToLF("a\r\nb\r\nc")).isEqualTo("a\nb\nc");
        assertThat(EditDiff.normalizeToLF("a\rb")).isEqualTo("a\nb");
    }

    @Test
    void applyReplacementsToOriginalKeepsUntouchedLineEndings() {
        // edit-diff.ts:21-61：只重写被触及的范围，未改行的 CRLF/LF 原样保留。
        String content = "a\r\nb\nc\r\n";
        var applied = EditDiff.applyEditsToNormalizedContent(
            EditDiff.normalizeToLF(content),
            List.of(new EditDiff.Edit("b", "B")), "f.txt");
        assertThat(EditDiff.applyReplacementsToOriginal(content, applied.replacements()))
            .isEqualTo("a\r\nB\nc\r\n");
    }

    @Test
    void newTextTakesTheLineEndingOfTheReplacedLine() {
        // 新文本内部的 \n 取替换起点所在行的行尾：CRLF 文件里插入两行仍是 CRLF。
        String content = "a\r\nb\r\nc";
        var applied = EditDiff.applyEditsToNormalizedContent(
            EditDiff.normalizeToLF(content),
            List.of(new EditDiff.Edit("b", "B1\nB2")), "f.txt");
        assertThat(EditDiff.applyReplacementsToOriginal(content, applied.replacements()))
            .isEqualTo("a\r\nB1\r\nB2\r\nc");
    }

    @Test
    void classicMacLoneCrIsTheLineEndingWhenNoLfExists() {
        String content = "a\rb\rc";
        var applied = EditDiff.applyEditsToNormalizedContent(
            EditDiff.normalizeToLF(content),
            List.of(new EditDiff.Edit("b", "B1\nB2")), "f.txt");
        assertThat(EditDiff.applyReplacementsToOriginal(content, applied.replacements()))
            .isEqualTo("a\rB1\rB2\rc");
    }

    @Test
    void crAdjacentToInsertedLfDoesNotMergeIntoCrlf() {
        // 缝上保留的孤立 CR 与插入文本开头的 LF 不能合并成 CRLF（pi push 补 LF）。
        String content = "a\rb\nc";
        var applied = EditDiff.applyEditsToNormalizedContent(
            EditDiff.normalizeToLF(content),
            List.of(new EditDiff.Edit("b", "\nZ")), "f.txt");
        assertThat(EditDiff.applyReplacementsToOriginal(content, applied.replacements()))
            .isEqualTo("a\r\n\nZ\nc");
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
