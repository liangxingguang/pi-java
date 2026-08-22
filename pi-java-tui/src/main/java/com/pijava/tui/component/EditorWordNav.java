package com.pijava.tui.component;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;

/**
 * Word-boundary and grapheme helpers for the editor (pi {@code tui/word-navigation.ts}).
 *
 * <p>{@code findWordBackward}/{@code findWordForward} port pi's Intl.Segmenter
 * based word navigation: skip whitespace, then step one word/punctuation unit,
 * preserving ASCII punctuation boundaries inside word-like runs. The
 * {@code graphemeToChar}/{@code charToGrapheme}/{@code graphemeCount} helpers
 * translate between TamboUI {@code TextAreaState}'s grapheme-column cursor and
 * UTF-16 char offsets.</p>
 */
final class EditorWordNav {

    /** pi {@code PUNCTUATION_REGEX} character class. */
    private static final String PUNCTUATION = "(){}[]<>.,;:'\"!?+-=*/\\|&%^$#@~`";

    private enum Type { WHITESPACE, WORD, PUNCT, OTHER }

    private record Segment(String text, Type type) {
        int length() {
            return text.length();
        }

        boolean isWordLike() {
            return type == Type.WORD;
        }
    }

    private EditorWordNav() {
    }

    // ── Word navigation (pi findWordBackward / findWordForward) ──────────

    /** Cursor position after moving one word backward (pi semantics). */
    static int findWordBackward(String text, int cursor) {
        if (cursor <= 0) {
            return 0;
        }
        var segments = segment(text.substring(0, cursor));
        int newCursor = cursor;
        // Skip trailing whitespace.
        while (!segments.isEmpty() && segments.getLast().type() == Type.WHITESPACE) {
            newCursor -= segments.removeLast().length();
        }
        if (segments.isEmpty()) {
            return newCursor;
        }
        var last = segments.getLast();
        if (last.isWordLike()) {
            // Skip one word-like segment, preserving ASCII punctuation boundaries.
            int lastPunctEnd = lastPunctEnd(last.text());
            newCursor -= lastPunctEnd < 0 ? last.length() : last.length() - lastPunctEnd;
        } else {
            // Skip the whole non-word non-whitespace run (punctuation).
            while (!segments.isEmpty()
                    && segments.getLast().type() != Type.WHITESPACE
                    && !segments.getLast().isWordLike()) {
                newCursor -= segments.removeLast().length();
            }
        }
        return newCursor;
    }

    /** Cursor position after moving one word forward (pi semantics). */
    static int findWordForward(String text, int cursor) {
        if (cursor >= text.length()) {
            return text.length();
        }
        var segments = segment(text.substring(cursor));
        int idx = 0;
        int newCursor = cursor;
        // Skip leading whitespace.
        while (idx < segments.size() && segments.get(idx).type() == Type.WHITESPACE) {
            newCursor += segments.get(idx).length();
            idx++;
        }
        if (idx >= segments.size()) {
            return newCursor;
        }
        var next = segments.get(idx);
        if (next.isWordLike()) {
            // Stop at the first ASCII punctuation inside the word-like segment.
            int firstPunct = firstPunctIndex(next.text());
            newCursor += firstPunct < 0 ? next.length() : firstPunct;
        } else {
            // Skip the whole non-word non-whitespace run (punctuation).
            while (idx < segments.size()
                    && segments.get(idx).type() != Type.WHITESPACE
                    && !segments.get(idx).isWordLike()) {
                newCursor += segments.get(idx).length();
                idx++;
            }
        }
        return newCursor;
    }

    // ── Grapheme ↔ char offset (TextAreaState cursor is grapheme-based) ──

    /** Grapheme count of {@code s} (each {@code '\n'} is its own cluster). */
    static int graphemeCount(String s) {
        var it = BreakIterator.getCharacterInstance();
        it.setText(s);
        int count = 0;
        while (it.next() != BreakIterator.DONE) {
            count++;
        }
        return count;
    }

    /** UTF-16 char offset of the {@code graphemeCol}-th grapheme in {@code line}. */
    static int graphemeToChar(String line, int graphemeCol) {
        var it = BreakIterator.getCharacterInstance();
        it.setText(line);
        int boundary = it.first();
        int i = 0;
        while (i < graphemeCol) {
            int next = it.next();
            if (next == BreakIterator.DONE) {
                return line.length();
            }
            boundary = next;
            i++;
        }
        return boundary;
    }

    /** Grapheme column of the char at {@code charOffset} in {@code line}. */
    static int charToGrapheme(String line, int charOffset) {
        var it = BreakIterator.getCharacterInstance();
        it.setText(line);
        int count = 0;
        int boundary = it.first();
        while (boundary != BreakIterator.DONE && boundary < charOffset) {
            boundary = it.next();
            count++;
        }
        return count;
    }

    // ── Segmentation (approximates Intl.Segmenter word granularity) ──────

    private static List<Segment> segment(String text) {
        var segments = new ArrayList<Segment>();
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            Type type = typeOf(cp);
            int start = i;
            i += Character.charCount(cp);
            while (i < text.length() && typeOf(text.codePointAt(i)) == type) {
                i += Character.charCount(text.codePointAt(i));
            }
            segments.add(new Segment(text.substring(start, i), type));
        }
        return segments;
    }

    private static Type typeOf(int cp) {
        if (Character.isWhitespace(cp)) {
            return Type.WHITESPACE;
        }
        if (isWordChar(cp)) {
            return Type.WORD;
        }
        if (PUNCTUATION.indexOf(cp) >= 0) {
            return Type.PUNCT;
        }
        return Type.OTHER;
    }

    private static boolean isWordChar(int cp) {
        return Character.isLetterOrDigit(cp) || cp == '_';
    }

    /** Index after the last punctuation char in {@code seg}, or -1. */
    private static int lastPunctEnd(String seg) {
        for (int i = seg.length() - 1; i >= 0; i--) {
            if (PUNCTUATION.indexOf(seg.charAt(i)) >= 0) {
                return i + 1;
            }
        }
        return -1;
    }

    /** Index of the first punctuation char in {@code seg}, or -1. */
    private static int firstPunctIndex(String seg) {
        for (int i = 0; i < seg.length(); i++) {
            if (PUNCTUATION.indexOf(seg.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }
}
