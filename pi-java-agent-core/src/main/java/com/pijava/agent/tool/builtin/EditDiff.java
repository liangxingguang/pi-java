package com.pijava.agent.tool.builtin;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Diff computation utilities for the edit tool (pi {@code harness/tools/edit-diff.ts}).
 *
 * <p>Ports pi's exact-text replacement semantics: all edits are matched against
 * the <em>original</em> LF-normalized content (exact match first, then a fuzzy
 * NFKC-normalized fallback), validated for uniqueness and overlap, then applied
 * in reverse order so earlier offsets stay stable. Line endings and a leading
 * BOM are preserved across the write.</p>
 */
final class EditDiff {

    /** A single {@code {oldText, newText}} replacement. */
    record Edit(String oldText, String newText) {
    }

    /** Result of {@link #applyEditsToNormalizedContent}. */
    record AppliedEdits(String baseContent, String newContent) {
    }

    /** Result of {@link #fuzzyFindText}. */
    record FuzzyMatch(boolean found, int index, int matchLength,
                      boolean usedFuzzyMatch, String contentForReplacement) {
    }

    /** A matched edit plus the offsets it occupies in the base content. */
    private record MatchedEdit(int editIndex, int matchIndex, int matchLength, String newText) {
    }

    private record LineSpan(int start, int end) {
    }

    private static final Pattern LINE_WITH_ENDINGS =
        Pattern.compile("[^\\n]*\\n|[^\\n]+");

    private EditDiff() {
    }

    // ── Line ending / BOM handling ──────────────────────────────────────

    /** Detect {@code \r\n} vs {@code \n} from the first occurrence. */
    static String detectLineEnding(String content) {
        int crlfIdx = content.indexOf("\r\n");
        int lfIdx = content.indexOf("\n");
        if (lfIdx == -1 || crlfIdx == -1) {
            return "\n";
        }
        return crlfIdx < lfIdx ? "\r\n" : "\n";
    }

    /** Normalize {@code \r\n} and {@code \r} to {@code \n}. */
    static String normalizeToLF(String text) {
        return text.replace("\r\n", "\n").replace("\r", "\n");
    }

    /** Restore a normalized LF text to the detected line ending. */
    static String restoreLineEndings(String text, String ending) {
        return "\r\n".equals(ending) ? text.replace("\n", "\r\n") : text;
    }

    /** Strip a leading UTF-8 BOM, returning it separately. */
    record Bom(String bom, String text) {
    }

    static Bom stripBom(String content) {
        return content.startsWith("\uFEFF")
            ? new Bom("\uFEFF", content.substring(1))
            : new Bom("", content);
    }

    // ── Fuzzy matching ──────────────────────────────────────────────────

    /**
     * Normalize text for fuzzy matching (pi {@code normalizeForFuzzyMatch}):
     * NFKC, per-line trailing whitespace stripped, smart quotes/dashes/spaces
     * normalized to ASCII.
     */
    static String normalizeForFuzzyMatch(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
        var sb = new StringBuilder();
        String[] lines = normalized.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(lines[i].stripTrailing());
        }
        String result = sb.toString()
            .replaceAll("[\\u2018\\u2019\\u201A\\u201B]", "'")
            .replaceAll("[\\u201C\\u201D\\u201E\\u201F]", "\"")
            .replaceAll("[\\u2010\\u2011\\u2012\\u2013\\u2014\\u2015\\u2212]", "-")
            .replaceAll("[\\u00A0\\u2002-\\u200A\\u202F\\u205F\\u3000]", " ");
        return result;
    }

    /**
     * Find {@code oldText} in {@code content}, trying an exact match first,
     * then a fuzzy NFKC-normalized match. When fuzzy, the returned
     * {@code contentForReplacement} is the normalized content.
     */
    static FuzzyMatch fuzzyFindText(String content, String oldText) {
        int exactIndex = content.indexOf(oldText);
        if (exactIndex != -1) {
            return new FuzzyMatch(true, exactIndex, oldText.length(), false, content);
        }
        String fuzzyContent = normalizeForFuzzyMatch(content);
        String fuzzyOldText = normalizeForFuzzyMatch(oldText);
        int fuzzyIndex = fuzzyContent.indexOf(fuzzyOldText);
        if (fuzzyIndex == -1) {
            return new FuzzyMatch(false, -1, 0, false, content);
        }
        return new FuzzyMatch(true, fuzzyIndex, fuzzyOldText.length(), true, fuzzyContent);
    }

    // ── Edit application ────────────────────────────────────────────────

    /**
     * Apply one or more exact-text replacements to LF-normalized content.
     * All edits are matched against the same original content; replacements are
     * applied in reverse order so offsets remain stable.
     *
     * @param normalizedContent the LF-normalized file content
     * @param edits             the replacements (oldText must be unique + non-overlapping)
     * @param path              file path, used in error messages
     * @return the base content (unchanged) and the new content
     */
    static AppliedEdits applyEditsToNormalizedContent(
            String normalizedContent, List<Edit> edits, String path) {
        var normalizedEdits = edits.stream()
            .map(e -> new Edit(normalizeToLF(e.oldText()), normalizeToLF(e.newText())))
            .toList();

        for (int i = 0; i < normalizedEdits.size(); i++) {
            if (normalizedEdits.get(i).oldText().isEmpty()) {
                throw emptyOldTextError(path, i, normalizedEdits.size());
            }
        }

        boolean usedFuzzyMatch = normalizedEdits.stream()
            .anyMatch(e -> fuzzyFindText(normalizedContent, e.oldText()).usedFuzzyMatch());
        String replacementBaseContent = usedFuzzyMatch
            ? normalizeForFuzzyMatch(normalizedContent) : normalizedContent;

        var matchedEdits = new ArrayList<MatchedEdit>();
        for (int i = 0; i < normalizedEdits.size(); i++) {
            var edit = normalizedEdits.get(i);
            var matchResult = fuzzyFindText(replacementBaseContent, edit.oldText());
            if (!matchResult.found()) {
                throw notFoundError(path, i, normalizedEdits.size());
            }
            int occurrences = countOccurrences(replacementBaseContent, edit.oldText());
            if (occurrences > 1) {
                throw duplicateError(path, i, normalizedEdits.size(), occurrences);
            }
            matchedEdits.add(new MatchedEdit(i, matchResult.index(),
                matchResult.matchLength(), edit.newText()));
        }

        matchedEdits.sort(Comparator.comparingInt(MatchedEdit::matchIndex));
        for (int i = 1; i < matchedEdits.size(); i++) {
            var previous = matchedEdits.get(i - 1);
            var current = matchedEdits.get(i);
            if (previous.matchIndex() + previous.matchLength() > current.matchIndex()) {
                throw new IllegalArgumentException("edits[" + previous.editIndex()
                    + "] and edits[" + current.editIndex() + "] overlap in " + path
                    + ". Merge them into one edit or target disjoint regions.");
            }
        }

        String baseContent = normalizedContent;
        String newContent = usedFuzzyMatch
            ? applyReplacementsPreservingUnchangedLines(
                normalizedContent, replacementBaseContent, matchedEdits)
            : applyReplacements(replacementBaseContent, matchedEdits, 0);

        if (baseContent.equals(newContent)) {
            throw noChangeError(path, normalizedEdits.size());
        }
        return new AppliedEdits(baseContent, newContent);
    }

    private static int countOccurrences(String content, String oldText) {
        String fuzzyContent = normalizeForFuzzyMatch(content);
        String fuzzyOldText = normalizeForFuzzyMatch(oldText);
        int count = 0;
        int from = 0;
        int idx;
        while ((idx = fuzzyContent.indexOf(fuzzyOldText, from)) != -1) {
            count++;
            from = idx + fuzzyOldText.length();
        }
        return count;
    }

    // ── Replacement application (reverse order + unchanged-line overlay) ─

    private static String applyReplacements(String content, List<MatchedEdit> replacements, int offset) {
        String result = content;
        for (int i = replacements.size() - 1; i >= 0; i--) {
            var replacement = replacements.get(i);
            int matchIndex = replacement.matchIndex() - offset;
            result = result.substring(0, matchIndex) + replacement.newText()
                + result.substring(matchIndex + replacement.matchLength());
        }
        return result;
    }

    private static List<String> splitLinesWithEndings(String content) {
        var lines = new ArrayList<String>();
        var matcher = LINE_WITH_ENDINGS.matcher(content);
        while (matcher.find()) {
            lines.add(matcher.group());
        }
        return lines;
    }

    private static List<LineSpan> getLineSpans(String content) {
        int offset = 0;
        var spans = new ArrayList<LineSpan>();
        for (String line : splitLinesWithEndings(content)) {
            spans.add(new LineSpan(offset, offset + line.length()));
            offset += line.length();
        }
        return spans;
    }

    private static LineRange getReplacementLineRange(List<LineSpan> lines, MatchedEdit replacement) {
        int replacementStart = replacement.matchIndex();
        int replacementEnd = replacement.matchIndex() + replacement.matchLength();

        int startLine = -1;
        for (int i = 0; i < lines.size(); i++) {
            var line = lines.get(i);
            if (replacementStart >= line.start() && replacementStart < line.end()) {
                startLine = i;
                break;
            }
        }
        if (startLine == -1) {
            throw new IllegalArgumentException("Replacement range is outside the base content.");
        }

        int endLine = startLine;
        while (endLine < lines.size() && lines.get(endLine).end() < replacementEnd) {
            endLine++;
        }
        if (endLine >= lines.size()) {
            throw new IllegalArgumentException("Replacement range is outside the base content.");
        }
        return new LineRange(startLine, endLine + 1);
    }

    private record LineRange(int startLine, int endLine) {
    }

    /**
     * Apply replacements matched against {@code baseContent} to
     * {@code originalContent} while preserving unchanged line blocks from the
     * original (used when fuzzy matching rewrote unchanged lines).
     */
    private static String applyReplacementsPreservingUnchangedLines(
            String originalContent, String baseContent, List<MatchedEdit> replacements) {
        var originalLines = splitLinesWithEndings(originalContent);
        var baseLines = getLineSpans(baseContent);
        if (originalLines.size() != baseLines.size()) {
            throw new IllegalArgumentException(
                "Cannot preserve unchanged lines because the base content has a different line count.");
        }

        record Group(int startLine, int endLine, List<MatchedEdit> replacements) {
        }
        var groups = new ArrayList<Group>();
        var sortedReplacements = new ArrayList<>(replacements);
        sortedReplacements.sort(Comparator.comparingInt(MatchedEdit::matchIndex));
        for (var replacement : sortedReplacements) {
            var range = getReplacementLineRange(baseLines, replacement);
            if (!groups.isEmpty()) {
                var current = groups.get(groups.size() - 1);
                if (range.startLine() < current.endLine()) {
                    groups.set(groups.size() - 1, new Group(current.startLine(),
                        Math.max(current.endLine(), range.endLine()),
                        withAppended(current.replacements(), replacement)));
                    continue;
                }
            }
            groups.add(new Group(range.startLine(), range.endLine(), List.of(replacement)));
        }

        int originalLineIndex = 0;
        var result = new StringBuilder();
        for (var group : groups) {
            for (int k = originalLineIndex; k < group.startLine(); k++) {
                result.append(originalLines.get(k));
            }
            int groupStartOffset = baseLines.get(group.startLine()).start();
            int groupEndOffset = baseLines.get(group.endLine() - 1).end();
            result.append(applyReplacements(
                baseContent.substring(groupStartOffset, groupEndOffset),
                group.replacements(), groupStartOffset));
            originalLineIndex = group.endLine();
        }
        for (int k = originalLineIndex; k < originalLines.size(); k++) {
            result.append(originalLines.get(k));
        }
        return result.toString();
    }

    private static List<MatchedEdit> withAppended(List<MatchedEdit> list, MatchedEdit edit) {
        var result = new ArrayList<>(list);
        result.add(edit);
        return result;
    }

    // ── Error messages (pi text) ────────────────────────────────────────

    private static IllegalArgumentException notFoundError(String path, int editIndex, int totalEdits) {
        String message = totalEdits == 1
            ? "Could not find the exact text in " + path
                + ". The old text must match exactly including all whitespace and newlines."
            : "Could not find edits[" + editIndex + "] in " + path
                + ". The oldText must match exactly including all whitespace and newlines.";
        return new IllegalArgumentException(message);
    }

    private static IllegalArgumentException duplicateError(String path, int editIndex, int totalEdits, int occurrences) {
        String message = totalEdits == 1
            ? "Found " + occurrences + " occurrences of the text in " + path
                + ". The text must be unique. Please provide more context to make it unique."
            : "Found " + occurrences + " occurrences of edits[" + editIndex + "] in " + path
                + ". Each oldText must be unique. Please provide more context to make it unique.";
        return new IllegalArgumentException(message);
    }

    private static IllegalArgumentException emptyOldTextError(String path, int editIndex, int totalEdits) {
        String message = totalEdits == 1
            ? "oldText must not be empty in " + path + "."
            : "edits[" + editIndex + "].oldText must not be empty in " + path + ".";
        return new IllegalArgumentException(message);
    }

    private static IllegalArgumentException noChangeError(String path, int totalEdits) {
        String message = totalEdits == 1
            ? "No changes made to " + path
                + ". The replacement produced identical content. This might indicate an issue with special characters or the text not existing as expected."
            : "No changes made to " + path + ". The replacements produced identical content.";
        return new IllegalArgumentException(message);
    }
}
