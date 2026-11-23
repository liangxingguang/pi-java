package com.pijava.tui.component;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.tamboui.widgets.input.TextAreaState;

/**
 * Large-paste folding and marker registry (pi {@code editor.ts:1276-1345},
 * {@code :1411-1433}, {@code :1106-1120}).
 *
 * <p>Pastes over 10 lines or 1000 characters are stored in a registry and
 * replaced by an atomic-looking marker such as {@code [paste #1 +11 lines]};
 * the marker expands again on submit. Deleting a marker removes its registry
 * entry, shifts higher ids down, and renumbers the remaining markers.</p>
 */
final class EditorPaste {

    /** Full paste marker (pi editor.ts:31-35). */
    private static final Pattern MARKER =
        Pattern.compile("\\[paste #(\\d+)( (\\+\\d+ lines|\\d+ chars))?\\]");
    /** Marker sitting at the end of the text before the cursor (backspace arm). */
    private static final Pattern MARKER_END =
        Pattern.compile("\\[paste #(\\d+)( (\\+\\d+ lines|\\d+ chars))?\\]$");

    private final TextAreaState state;
    private final Map<Integer, String> pastes = new HashMap<>();
    private int pasteCounter;

    EditorPaste(TextAreaState state) {
        this.state = state;
    }

    Map<Integer, String> snapshotPastes() {
        return pastes.isEmpty() ? Map.of() : new HashMap<>(pastes);
    }

    int counter() {
        return pasteCounter;
    }

    void restore(Map<Integer, String> snapshot, int counter) {
        pastes.clear();
        pastes.putAll(snapshot);
        pasteCounter = counter;
    }

    void clear() {
        pastes.clear();
        pasteCounter = 0;
    }

    /** Insert pasted text at the cursor, folding large pastes into a marker. */
    void insert(String text) {
        String normalized = normalizeText(text);
        StringBuilder filtered = new StringBuilder(normalized.length());
        normalized.codePoints().forEach(cp -> {
            if (cp == '\n' || cp >= 32) {
                filtered.appendCodePoint(cp);
            }
        });
        String pasted = filtered.toString();
        if (pasted.isEmpty()) {
            return;
        }
        // Pasting a file path with a word character before the cursor adds a space.
        if (pasted.charAt(0) == '/' || pasted.charAt(0) == '~' || pasted.charAt(0) == '.') {
            String line = state.getLine(state.cursorRow());
            int charCol = EditorWordNav.graphemeToChar(line, state.cursorCol());
            if (charCol > 0 && isWordChar(line.charAt(charCol - 1))) {
                pasted = " " + pasted;
            }
        }
        int lineCount = pasted.split("\n", -1).length;
        int totalChars = pasted.length();
        if (lineCount > 10 || totalChars > 1000) {
            pasteCounter++;
            pastes.put(pasteCounter, pasted);
            String marker = lineCount > 10
                ? "[paste #" + pasteCounter + " +" + lineCount + " lines]"
                : "[paste #" + pasteCounter + " " + totalChars + " chars]";
            state.insert(marker);
            return;
        }
        state.insert(pasted);
    }

    /** Delete a whole marker before the cursor; returns true when one was removed. */
    boolean deleteBackward(Runnable pushSnapshot) {
        if (state.cursorCol() == 0) {
            return false;
        }
        String line = state.getLine(state.cursorRow());
        int charCol = EditorWordNav.graphemeToChar(line, state.cursorCol());
        Matcher matcher = MARKER_END.matcher(line.substring(0, charCol));
        if (!matcher.find()) {
            return false;
        }
        int id = Integer.parseInt(matcher.group(1));
        int end = charOffsetOf(state.cursorRow(), charCol);
        int start = end - matcher.group().length();
        pushSnapshot.run();
        removeMarkerRange(start, end, id, start);
        return true;
    }

    /** Delete a whole marker starting at the cursor; cursor stays in place. */
    boolean deleteForward(Runnable pushSnapshot) {
        String line = state.getLine(state.cursorRow());
        int charCol = EditorWordNav.graphemeToChar(line, state.cursorCol());
        Matcher matcher = MARKER.matcher(line);
        if (!matcher.find(charCol) || matcher.start() != charCol) {
            return false;
        }
        int id = Integer.parseInt(matcher.group(1));
        int start = charOffsetOf(state.cursorRow(), charCol);
        pushSnapshot.run();
        removeMarkerRange(start, start + matcher.group().length(), id, start);
        return true;
    }

    /** Expand every registered marker back to its content (pi expandPasteMarkers). */
    String expand(String text) {
        String result = text;
        for (var entry : pastes.entrySet()) {
            result = result.replaceAll(
                "\\[paste #" + entry.getKey() + "( (\\+\\d+ lines|\\d+ chars))?\\]",
                Matcher.quoteReplacement(entry.getValue()));
        }
        return result;
    }

    // ── internals ──────────────────────────────────────────────────────

    private int charOffsetOf(int row, int charCol) {
        int offset = 0;
        for (int r = 0; r < row && r < state.lineCount(); r++) {
            offset += state.getLine(r).length() + 1;
        }
        return offset + charCol;
    }

    /**
     * Remove {@code [start,end)}, unregister the paste, shift higher ids down,
     * renumber remaining markers, and move the cursor to {@code cursorAnchor}
     * (adjusted for renumbering length changes before the anchor).
     */
    private void removeMarkerRange(int start, int end, int id, int cursorAnchor) {
        String text = state.text();
        String beforeAnchor = text.substring(0, Math.min(cursorAnchor, text.length()));
        Matcher earlier = MARKER.matcher(beforeAnchor);
        int shift = 0;
        while (earlier.find()) {
            if (Integer.parseInt(earlier.group(1)) > id) {
                shift++;
            }
        }
        String removed = text.substring(0, start) + text.substring(end);
        String renumbered = renumber(removed, id);
        int newAnchor = Math.max(0, Math.min(cursorAnchor - shift, renumbered.length()));

        pastes.remove(id);
        pasteCounter = Math.max(0, pasteCounter - 1);
        var higher = pastes.keySet().stream().filter(k -> k > id).sorted().toList();
        for (int hid : higher) {
            pastes.put(hid - 1, pastes.remove(hid));
        }

        state.setText(renumbered);
        moveCursorToAbsolute(newAnchor);
        state.ensureCursorVisible(state.lineCount(), 80);
    }

    /** Decrement every marker id greater than {@code removedId}. */
    private static String renumber(String text, int removedId) {
        Matcher matcher = MARKER.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            int id = Integer.parseInt(matcher.group(1));
            String replacement = id <= removedId
                ? matcher.group()
                : "[paste #" + (id - 1) + (matcher.group(2) == null ? "" : matcher.group(2)) + "]";
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private void moveCursorToAbsolute(int absolute) {
        String text = state.text();
        int limit = Math.max(0, Math.min(absolute, text.length()));
        int row = 0;
        int lineStart = 0;
        for (int i = 0; i < limit; i++) {
            if (text.charAt(i) == '\n') {
                row++;
                lineStart = i + 1;
            }
        }
        String line = state.getLine(row);
        int charCol = Math.min(limit - lineStart, line.length());
        moveCursorTo(row, EditorWordNav.charToGrapheme(line, charCol));
    }

    private void moveCursorTo(int row, int graphemeCol) {
        state.moveCursorToStart();
        for (int i = 0; i < row; i++) {
            state.moveCursorDown();
        }
        state.moveCursorToLineStart();
        for (int i = 0; i < graphemeCol; i++) {
            state.moveCursorRight();
        }
    }

    /** pi normalizeText: CRLF/CR to LF, tab to four spaces. */
    private static String normalizeText(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ");
    }

    /** JS {@code \w}: ASCII letter, digit, underscore. */
    private static boolean isWordChar(char c) {
        return c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
            || (c >= '0' && c <= '9');
    }
}
