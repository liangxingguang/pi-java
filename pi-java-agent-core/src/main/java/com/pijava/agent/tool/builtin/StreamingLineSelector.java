package com.pijava.agent.tool.builtin;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import com.pijava.agent.tool.TruncationUtils;
import com.pijava.ai.AbortSignal;

/**
 * Selects the lines the read tool will show straight from a byte stream, keeping in memory only
 * what {@link TruncationUtils#truncateHead} can display（pi {@code selectLinesFromStream}，
 * read.ts:142-228）。
 *
 * <p>与整文件路径（{@code String} 载入后 slice）逐字节等价：按 {@code \n} 切行、保留 BOM、
 * 非致命 UTF-8（畸形字节替换成 U+FFFD），选中 {@code [startLine, startLine+limit)}，
 * 再交给整文件用的同一 {@code truncateHead}。行数／字节总数在流式过程中单独累计，
 * 因此超过 50KB 的行不会被整行收进内存。</p>
 */
final class StreamingLineSelector {
    private static final int CHUNK_CHARS = 16 * 1024;

    private StreamingLineSelector() {}

    /** The selected lines, with pi's {@code SelectedLines} fields. */
    record Selection(TruncationUtils.TruncationResult truncation,
            int totalFileLines, int firstLineBytes) {}

    static Selection select(InputStream in, int startLine, Integer limit, AbortSignal signal)
            throws IOException {
        int endLine = limit == null ? Integer.MAX_VALUE : startLine + limit;
        var builder = new Builder(startLine, endLine);
        try (var reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            var buffer = new char[CHUNK_CHARS];
            int n;
            while ((n = reader.read(buffer)) >= 0) {
                if (signal != null && signal.isAborted()) {
                    throw new IOException("Operation aborted");
                }
                if (n > 0) {
                    builder.process(new String(buffer, 0, n));
                }
            }
        }
        builder.finishFinalLine();
        return builder.toSelection();
    }

    /** Stream cursor: line index, selected range, and the capped storage of shown lines. */
    private static final class Builder {
        private final int startLine;
        private final int endLine;
        private final ArrayList<String> stored = new ArrayList<>();
        private int storedBytes;
        private boolean storedPastCaps;
        private int line;
        private int selectedLines;
        private int selectedBytes;
        private int firstLineBytes;
        private boolean lastLineEmpty = true;
        private boolean storeLine = true;
        private StringBuilder lineText = new StringBuilder();
        private int lineBytes;
        private boolean lineHasText;

        Builder(int startLine, int endLine) {
            this.startLine = startLine;
            this.endLine = endLine;
        }

        private boolean selected() {
            return line >= startLine && line < endLine;
        }

        void process(String text) {
            int position = 0;
            while (true) {
                int newline = text.indexOf('\n', position);
                int end = newline < 0 ? text.length() : newline;
                if (selected() && end > position) {
                    appendPiece(text.substring(position, end));
                }
                if (newline < 0) {
                    return;
                }
                lineBreak();
                position = newline + 1;
            }
        }

        /** Append a decoded piece of the current line, mirroring pi's {@code appendToLine}. */
        private void appendPiece(String piece) {
            lineHasText = true;
            if (storeLine && lineBytes <= TruncationUtils.DEFAULT_MAX_BYTES) {
                lineText.append(piece);
            }
            lineBytes += piece.getBytes(StandardCharsets.UTF_8).length;
        }

        /** A {@code \n} ends the current line. */
        private void lineBreak() {
            if (selected()) {
                recordSelectedLine();
                if (line + 1 < endLine) {
                    selectedBytes += 1;   // the newline stays in the selected slice
                }
                storeCurrentLine();
            }
            lineText = new StringBuilder();
            storeLine = !storedPastCaps;
            lineBytes = 0;
            lineHasText = false;
            line++;
        }

        /** EOF finishes the line in progress; its text is not followed by a newline. */
        void finishFinalLine() {
            if (selected()) {
                recordSelectedLine();
                storeCurrentLine();
            }
        }

        private void recordSelectedLine() {
            if (selectedLines == 0) {
                firstLineBytes = lineBytes;
            }
            selectedLines++;
            lastLineEmpty = !lineHasText;
            selectedBytes += lineBytes;
        }

        private void storeCurrentLine() {
            if (!storeLine) {
                return;
            }
            // Keep one line past the caps so truncateHead decides as for the full text.
            if (stored.size() > TruncationUtils.DEFAULT_MAX_LINES
                    || storedBytes > TruncationUtils.DEFAULT_MAX_BYTES) {
                storedPastCaps = true;
            }
            String text = lineText.toString();
            storedBytes += text.getBytes(StandardCharsets.UTF_8).length
                + (stored.isEmpty() ? 0 : 1);
            stored.add(text);
        }

        Selection toSelection() {
            var truncation = TruncationUtils.truncateHead(String.join("\n", stored));
            // pi overrides truncateHead's counts with the real selected totals: truncateHead drops
            // a trailing empty line, and storage stopped at the caps.
            int totalLines = selectedBytes == 0 ? 0 : selectedLines - (lastLineEmpty ? 1 : 0);
            var result = new TruncationUtils.TruncationResult(
                truncation.content(), truncation.truncated(), truncation.truncatedBy(),
                totalLines, selectedBytes, truncation.outputLines(), truncation.outputBytes(),
                false, truncation.firstLineExceedsLimit(), truncation.maxLines(),
                truncation.maxBytes());
            return new Selection(result, line + 1, firstLineBytes);
        }
    }
}
