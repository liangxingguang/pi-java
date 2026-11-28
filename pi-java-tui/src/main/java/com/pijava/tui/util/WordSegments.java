package com.pijava.tui.util;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Word segmentation for mouse selection, a Java counterpart of pi
 * {@code tui-alt-screen.ts:1192-1227} ({@code getWordSelection}) with the
 * iTerm2 "characters considered part of a word" joiner set.
 *
 * <p>{@link java.text.BreakIterator} approximates the upstream
 * {@code Intl.Segmenter}: word-like runs, whitespace and punctuation are
 * separate segments, and joiner characters ({@code /-+~_.}) form their own
 * segments so that adjacent selectable segments join across them. CJK runs are
 * split per code point (the upstream dictionary segmentation is not
 * reproducible here).</p>
 */
public final class WordSegments {

    private static final String JOINERS = "/-+\\~_.";

    /** Display-column range, end exclusive. */
    public record Range(int start, int end) {}

    private record Segment(int start, int end, boolean selectable, boolean joiner) {}

    private WordSegments() {}

    /**
     * Returns the selectable word range at display column {@code col}, or
     * {@code null} when the line is empty or the column is out of range.
     *
     * <p>Mirrors pi: clicking whitespace or punctuation yields that segment
     * itself (not {@code null}); only a blank/out-of-range click has no range.</p>
     *
     * @param plainText visible line text (no markup)
     * @param col       display column
     * @return word range (end exclusive), or null
     */
    public static Range wordRangeAt(String plainText, int col) {
        if (plainText == null || plainText.isEmpty() || col < 0
                || col >= TextLayout.displayWidth(plainText)) {
            return null;
        }
        List<Segment> segments = segment(plainText);
        int clicked = -1;
        for (int i = 0; i < segments.size(); i++) {
            Segment segment = segments.get(i);
            if (col >= segment.start() && col < segment.end()) {
                clicked = i;
                break;
            }
        }
        if (clicked < 0) {
            return null;
        }
        int start = segments.get(clicked).start();
        int end = segments.get(clicked).end();
        for (int i = clicked; i > 0 && canJoin(segments.get(i - 1), segments.get(i)); i--) {
            start = segments.get(i - 1).start();
        }
        for (int i = clicked; i < segments.size() - 1 && canJoin(segments.get(i), segments.get(i + 1)); i++) {
            end = segments.get(i + 1).end();
        }
        return new Range(start, end);
    }

    /** Double-click window shared by mouse selection (pi {@code :81}). */
    public static long doubleClickIntervalMs() {
        return 500;
    }

    private static boolean canJoin(Segment left, Segment right) {
        return left.selectable() && right.selectable() && (left.joiner() || right.joiner());
    }

    private static List<Segment> segment(String text) {
        var segments = new ArrayList<Segment>();
        var iterator = BreakIterator.getWordInstance(Locale.ROOT);
        iterator.setText(text);
        int col = 0;
        int begin = iterator.first();
        for (int end = iterator.next(); end != BreakIterator.DONE; begin = end, end = iterator.next()) {
            String raw = text.substring(begin, end);
            if (raw.isEmpty()) {
                continue;
            }
            for (int i = 0; i < raw.length(); ) {
                int cp = raw.codePointAt(i);
                int cpWidth = TextLayout.displayWidth(raw.substring(i, i + Character.charCount(cp)));
                if (isJoiner(cp)) {
                    segments.add(new Segment(col, col + cpWidth, true, true));
                    col += cpWidth;
                    i += Character.charCount(cp);
                    continue;
                }
                if (isCjk(cp)) {
                    // CJK 单字成段：上游按词典切词不可复现，取更细粒度。
                    segments.add(new Segment(col, col + cpWidth, true, false));
                    col += cpWidth;
                    i += Character.charCount(cp);
                    continue;
                }
                boolean selectable = Character.isLetterOrDigit(cp);
                int runStart = col;
                int runEnd = col + cpWidth;
                i += Character.charCount(cp);
                while (i < raw.length()) {
                    int next = raw.codePointAt(i);
                    if (isJoiner(next) || isCjk(next)) {
                        break;
                    }
                    int nextWidth = TextLayout.displayWidth(
                        raw.substring(i, i + Character.charCount(next)));
                    if (Character.isLetterOrDigit(next) != selectable) {
                        break;
                    }
                    runEnd += nextWidth;
                    i += Character.charCount(next);
                }
                segments.add(new Segment(runStart, runEnd, selectable, false));
                col = runEnd;
            }
        }
        return segments;
    }

    private static boolean isJoiner(int cp) {
        return JOINERS.indexOf(cp) >= 0;
    }

    private static boolean isCjk(int cp) {
        var script = Character.UnicodeScript.of(cp);
        return script == Character.UnicodeScript.HAN
            || script == Character.UnicodeScript.HIRAGANA
            || script == Character.UnicodeScript.KATAKANA
            || script == Character.UnicodeScript.HANGUL;
    }
}
