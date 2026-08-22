package com.pijava.agent.tool.builtin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Line-level diff utilities (pi {@code edit-diff.ts} display + patch output).
 *
 * <p>Computes an LCS-based line diff (Hirschberg: O(n·m) time, O(min(n,m))
 * space) and renders it two ways: {@link #generateDiffString} is the
 * display-oriented, line-numbered diff shown to the model, and
 * {@link #generateUnifiedPatch} produces a {@code patch -p0}-applyable unified
 * patch. {@code DiffPart} runs mirror {@code Diff.diffLines} parts.</p>
 */
final class LineDiff {

    /** A run of lines that are all added, all removed, or all unchanged. */
    record DiffPart(boolean added, boolean removed, List<String> lines) {
        boolean changed() {
            return added || removed;
        }
    }

    /** Edit-script operation: {@code '='} unchanged, {@code '-'} removed, {@code '+'} added. */
    private record Op(char type, String line) {
    }

    private LineDiff() {
    }

    /** Split content into lines (newline separators dropped, trailing empty kept). */
    static List<String> splitLines(String content) {
        var lines = new ArrayList<String>();
        int start = 0;
        while (start <= content.length()) {
            int idx = content.indexOf('\n', start);
            if (idx == -1) {
                lines.add(content.substring(start));
                break;
            }
            lines.add(content.substring(start, idx));
            start = idx + 1;
        }
        return lines;
    }

    /** LCS-based diff of two contents as a sequence of typed runs. */
    static List<DiffPart> diffParts(String oldContent, String newContent) {
        var ops = new ArrayList<Op>();
        hirschberg(splitLines(oldContent), splitLines(newContent), ops);

        var parts = new ArrayList<DiffPart>();
        char current = 0;
        var currentLines = new ArrayList<String>();
        for (Op op : ops) {
            char type = op.type() == '=' ? ' ' : op.type();
            if (type != current) {
                if (current != 0) {
                    parts.add(new DiffPart(current == '+', current == '-',
                        List.copyOf(currentLines)));
                    currentLines.clear();
                }
                current = type;
            }
            currentLines.add(op.line());
        }
        if (current != 0) {
            parts.add(new DiffPart(current == '+', current == '-', List.copyOf(currentLines)));
        }
        return parts;
    }

    // ── Hirschberg LCS (space-efficient backtracking) ───────────────────

    private static void hirschberg(List<String> a, List<String> b, List<Op> ops) {
        if (a.isEmpty()) {
            for (String line : b) {
                ops.add(new Op('+', line));
            }
            return;
        }
        if (b.isEmpty()) {
            for (String line : a) {
                ops.add(new Op('-', line));
            }
            return;
        }
        if (a.size() == 1) {
            String only = a.get(0);
            int idx = b.indexOf(only);
            if (idx < 0) {
                ops.add(new Op('-', only));
                for (String line : b) {
                    ops.add(new Op('+', line));
                }
            } else {
                for (int j = 0; j < idx; j++) {
                    ops.add(new Op('+', b.get(j)));
                }
                ops.add(new Op('=', only));
                for (int j = idx + 1; j < b.size(); j++) {
                    ops.add(new Op('+', b.get(j)));
                }
            }
            return;
        }
        int mid = a.size() / 2;
        List<String> aLeft = a.subList(0, mid);
        List<String> aRight = a.subList(mid, a.size());
        int[] forward = lcsRow(aLeft, b);
        int[] reverse = lcsRowReversed(aRight, b);
        int bLen = b.size();
        int k = 0;
        int best = -1;
        for (int j = 0; j <= bLen; j++) {
            int value = forward[j] + reverse[bLen - j];
            if (value > best) {
                best = value;
                k = j;
            }
        }
        hirschberg(aLeft, b.subList(0, k), ops);
        hirschberg(aRight, b.subList(k, bLen), ops);
    }

    /** Last DP row of LCS(a, b) — {@code row[k]} = LCS length of a vs b[0..k). */
    private static int[] lcsRow(List<String> a, List<String> b) {
        int[] prev = new int[b.size() + 1];
        int[] cur = new int[b.size() + 1];
        for (String x : a) {
            for (int j = 0; j < b.size(); j++) {
                cur[j + 1] = x.equals(b.get(j))
                    ? prev[j] + 1
                    : Math.max(prev[j + 1], cur[j]);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev;
    }

    /** Last DP row of LCS(a, b) computed on the reversed sequences. */
    private static int[] lcsRowReversed(List<String> a, List<String> b) {
        var ra = new ArrayList<String>(a);
        Collections.reverse(ra);
        var rb = new ArrayList<String>(b);
        Collections.reverse(rb);
        return lcsRow(ra, rb);
    }

    // ── Display diff (pi generateDiffString) ────────────────────────────

    /** Display diff plus the first changed line in the new content (pi). */
    record DiffDisplay(String diff, Integer firstChangedLine) {
    }

    /**
     * Display-oriented diff with line numbers and context.
     * Mirrors pi {@code generateDiffString} semantics.
     */
    static DiffDisplay generateDiffString(String oldContent, String newContent, int contextLines) {
        var parts = diffParts(oldContent, newContent);
        var output = new ArrayList<String>();

        var oldLines = splitLines(oldContent);
        var newLines = splitLines(newContent);
        int maxLineNum = Math.max(oldLines.size(), newLines.size());
        int lineNumWidth = String.valueOf(maxLineNum).length();

        int oldLineNum = 1;
        int newLineNum = 1;
        boolean lastWasChange = false;
        Integer firstChangedLine = null;

        for (int i = 0; i < parts.size(); i++) {
            var part = parts.get(i);
            var raw = new ArrayList<>(part.lines());
            if (!raw.isEmpty() && raw.get(raw.size() - 1).isEmpty()) {
                raw.remove(raw.size() - 1);
            }

            if (part.changed()) {
                if (firstChangedLine == null) {
                    firstChangedLine = newLineNum;
                }
                for (String line : raw) {
                    if (part.added()) {
                        output.add("+" + pad(String.valueOf(newLineNum), lineNumWidth) + " " + line);
                        newLineNum++;
                    } else {
                        output.add("-" + pad(String.valueOf(oldLineNum), lineNumWidth) + " " + line);
                        oldLineNum++;
                    }
                }
                lastWasChange = true;
            } else {
                boolean nextPartIsChange = i < parts.size() - 1 && parts.get(i + 1).changed();
                boolean hasLeadingChange = lastWasChange;
                boolean hasTrailingChange = nextPartIsChange;

                if (hasLeadingChange && hasTrailingChange) {
                    if (raw.size() <= contextLines * 2) {
                        for (String line : raw) {
                            output.add(" " + pad(String.valueOf(oldLineNum), lineNumWidth) + " " + line);
                            oldLineNum++;
                            newLineNum++;
                        }
                    } else {
                        var leadingLines = raw.subList(0, contextLines);
                        var trailingLines = raw.subList(raw.size() - contextLines, raw.size());
                        int skippedLines = raw.size() - leadingLines.size() - trailingLines.size();

                        for (String line : leadingLines) {
                            output.add(" " + pad(String.valueOf(oldLineNum), lineNumWidth) + " " + line);
                            oldLineNum++;
                            newLineNum++;
                        }
                        output.add(" " + pad("", lineNumWidth) + " ...");
                        oldLineNum += skippedLines;
                        newLineNum += skippedLines;
                        for (String line : trailingLines) {
                            output.add(" " + pad(String.valueOf(oldLineNum), lineNumWidth) + " " + line);
                            oldLineNum++;
                            newLineNum++;
                        }
                    }
                } else if (hasLeadingChange) {
                    var shownLines = raw.subList(0, Math.min(contextLines, raw.size()));
                    int skippedLines = raw.size() - shownLines.size();
                    for (String line : shownLines) {
                        output.add(" " + pad(String.valueOf(oldLineNum), lineNumWidth) + " " + line);
                        oldLineNum++;
                        newLineNum++;
                    }
                    if (skippedLines > 0) {
                        output.add(" " + pad("", lineNumWidth) + " ...");
                        oldLineNum += skippedLines;
                        newLineNum += skippedLines;
                    }
                } else if (hasTrailingChange) {
                    int skippedLines = Math.max(0, raw.size() - contextLines);
                    if (skippedLines > 0) {
                        output.add(" " + pad("", lineNumWidth) + " ...");
                        oldLineNum += skippedLines;
                        newLineNum += skippedLines;
                    }
                    for (String line : raw.subList(skippedLines, raw.size())) {
                        output.add(" " + pad(String.valueOf(oldLineNum), lineNumWidth) + " " + line);
                        oldLineNum++;
                        newLineNum++;
                    }
                } else {
                    oldLineNum += raw.size();
                    newLineNum += raw.size();
                }
                lastWasChange = false;
            }
        }
        return new DiffDisplay(String.join("\n", output), firstChangedLine);
    }

    // ── Unified patch (patch -p0) ───────────────────────────────────────

    /** Generate a unified patch with the given context-line count. */
    static String generateUnifiedPatch(String oldPath, String newPath,
            String oldContent, String newContent, int contextLines) {
        var parts = diffParts(oldContent, newContent);
        var sb = new StringBuilder();
        sb.append("--- ").append(oldPath).append('\n');
        sb.append("+++ ").append(newPath).append('\n');

        int n = parts.size();
        int[] oldStart = new int[n];
        int[] newStart = new int[n];
        int oldLine = 1;
        int newLine = 1;
        for (int i = 0; i < n; i++) {
            oldStart[i] = oldLine;
            newStart[i] = newLine;
            var part = parts.get(i);
            if (part.removed()) {
                oldLine += part.lines().size();
            } else if (part.added()) {
                newLine += part.lines().size();
            } else {
                oldLine += part.lines().size();
                newLine += part.lines().size();
            }
        }

        int i = 0;
        while (i < n) {
            if (!parts.get(i).changed()) {
                i++;
                continue;
            }
            int start = i;
            int end = i;
            while (end + 1 < n) {
                int j = end + 1;
                int unchanged = 0;
                int nextChange = -1;
                while (j < n) {
                    if (parts.get(j).changed()) {
                        nextChange = j;
                        break;
                    }
                    unchanged += parts.get(j).lines().size();
                    j++;
                }
                if (nextChange != -1 && unchanged <= 2 * contextLines) {
                    end = nextChange;
                } else {
                    break;
                }
            }
            emitHunk(parts, oldStart, newStart, start, end, contextLines, sb);
            i = end + 1;
        }
        return sb.toString();
    }

    private static void emitHunk(List<DiffPart> parts, int[] oldStart, int[] newStart,
            int start, int end, int contextLines, StringBuilder sb) {
        var prefix = new ArrayList<String>();
        if (start > 0) {
            var prev = parts.get(start - 1);
            if (!prev.changed()) {
                int lines = prev.lines().size();
                int take = Math.min(contextLines, lines);
                for (int k = lines - take; k < lines; k++) {
                    prefix.add(prev.lines().get(k));
                }
            }
        }
        var suffix = new ArrayList<String>();
        if (end + 1 < parts.size()) {
            var next = parts.get(end + 1);
            if (!next.changed()) {
                int lines = next.lines().size();
                int take = Math.min(contextLines, lines);
                for (int k = 0; k < take; k++) {
                    suffix.add(next.lines().get(k));
                }
            }
        }

        var hunk = new ArrayList<String>();
        for (String line : prefix) {
            hunk.add(" " + line);
        }
        for (int k = start; k <= end; k++) {
            var part = parts.get(k);
            char marker = part.added() ? '+' : part.removed() ? '-' : ' ';
            for (String line : part.lines()) {
                hunk.add(marker + line);
            }
        }
        for (String line : suffix) {
            hunk.add(" " + line);
        }

        int oldCount = 0;
        int newCount = 0;
        for (String hunkLine : hunk) {
            char marker = hunkLine.charAt(0);
            if (marker == ' ') {
                oldCount++;
                newCount++;
            } else if (marker == '-') {
                oldCount++;
            } else {
                newCount++;
            }
        }
        int hunkOldStart = oldStart[start] - prefix.size();
        int hunkNewStart = newStart[start] - prefix.size();
        sb.append("@@ -").append(hunkOldStart).append(',').append(oldCount)
            .append(" +").append(hunkNewStart).append(',').append(newCount).append(" @@\n");
        for (String hunkLine : hunk) {
            sb.append(hunkLine).append('\n');
        }
    }

    private static String pad(String text, int width) {
        var sb = new StringBuilder(text);
        while (sb.length() < width) {
            sb.insert(0, ' ');
        }
        return sb.toString();
    }
}
