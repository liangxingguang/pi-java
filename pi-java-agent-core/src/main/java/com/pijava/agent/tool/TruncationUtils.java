package com.pijava.agent.tool;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * Output truncation for tools (bash, read).
 * Aligned with pi's truncate utilities.
 *
 * <p>Truncation strategy: first hit wins (lines or bytes).
 * "Head" truncation — keeps first N lines/bytes (used for read).
 * "Tail" truncation — keeps last N lines/bytes (used for bash).</p>
 */
public final class TruncationUtils {
    public static final int DEFAULT_MAX_LINES = 2000;
    public static final long DEFAULT_MAX_BYTES = 100_000L;

    private TruncationUtils() {}

    /** Format a byte size for display. */
    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
        return String.format("%.1fMB", bytes / (1024.0 * 1024.0));
    }

    /**
     * Truncate keeping the head (for read output).
     * Never returns partial lines. If first line exceeds byte limit,
     * returns empty content with firstLineExceedsLimit=true.
     */
    public static TruncationResult truncateHead(String content) {
        return truncateHead(content, DEFAULT_MAX_LINES, DEFAULT_MAX_BYTES);
    }

    /** Truncate keeping the head with explicit line and byte limits. */
    public static TruncationResult truncateHead(String content, int maxLines, long maxBytes) {
        int totalBytes = content.getBytes(StandardCharsets.UTF_8).length;
        String[] lines = splitLines(content);
        int totalLines = lines.length;

        if (totalLines <= maxLines && totalBytes <= maxBytes) {
            return new TruncationResult(content, false, null, totalLines, totalBytes,
                totalLines, totalBytes, false, false, maxLines, maxBytes);
        }

        // Check if first line alone exceeds byte limit
        int firstLineBytes = lines[0].getBytes(StandardCharsets.UTF_8).length;
        if (firstLineBytes > maxBytes) {
            return new TruncationResult("", true, "bytes", totalLines, totalBytes,
                0, 0, false, true, maxLines, maxBytes);
        }

        var outputLines = new ArrayList<String>();
        int outputBytesCount = 0;
        String truncatedBy = "lines";

        for (int i = 0; i < lines.length && i < maxLines; i++) {
            String line = lines[i];
            int lineBytes = line.getBytes(StandardCharsets.UTF_8).length;
            int newlineBytes = i > 0 ? 1 : 0;
            if (outputBytesCount + lineBytes + newlineBytes > maxBytes) {
                truncatedBy = "bytes";
                break;
            }
            outputLines.add(line);
            outputBytesCount += lineBytes + newlineBytes;
        }

        if (outputLines.size() >= maxLines && outputBytesCount <= maxBytes) {
            truncatedBy = "lines";
        }

        String outputContent = String.join("\n", outputLines);
        int finalOutputBytes = outputContent.getBytes(StandardCharsets.UTF_8).length;

        return new TruncationResult(outputContent, true, truncatedBy, totalLines, totalBytes,
            outputLines.size(), finalOutputBytes, false, false, maxLines, maxBytes);
    }

    /**
     * Truncate keeping the tail (for bash output).
     * May return partial first line if the last line exceeds byte limit.
     */
    public static TruncationResult truncateTail(String content) {
        return truncateTail(content, DEFAULT_MAX_LINES, DEFAULT_MAX_BYTES);
    }

    /** Truncate keeping the tail with explicit line and byte limits. */
    public static TruncationResult truncateTail(String content, int maxLines, long maxBytes) {
        int totalBytes = content.getBytes(StandardCharsets.UTF_8).length;
        String[] lines = splitLines(content);
        int totalLines = lines.length;

        if (totalLines <= maxLines && totalBytes <= maxBytes) {
            return new TruncationResult(content, false, null, totalLines, totalBytes,
                totalLines, totalBytes, false, false, maxLines, maxBytes);
        }

        var outputLines = new ArrayList<String>();
        int outputBytesCount = 0;
        String truncatedBy = "lines";
        boolean lastLinePartial = false;

        for (int i = lines.length - 1; i >= 0 && outputLines.size() < maxLines; i--) {
            String line = lines[i];
            int lineBytes = line.getBytes(StandardCharsets.UTF_8).length;
            int newlineBytes = outputLines.size() > 0 ? 1 : 0;

            if (outputBytesCount + lineBytes + newlineBytes > maxBytes) {
                truncatedBy = "bytes";
                if (outputLines.isEmpty()) {
                    String truncatedLine = truncateStringToBytesFromEnd(line, maxBytes);
                    outputLines.addFirst(truncatedLine);
                    outputBytesCount = truncatedLine.getBytes(StandardCharsets.UTF_8).length;
                    lastLinePartial = true;
                }
                break;
            }
            outputLines.addFirst(line);
            outputBytesCount += lineBytes + newlineBytes;
        }

        if (outputLines.size() >= maxLines && outputBytesCount <= maxBytes) {
            truncatedBy = "lines";
        }

        String outputContent = String.join("\n", outputLines);
        int finalOutputBytes = outputContent.getBytes(StandardCharsets.UTF_8).length;

        return new TruncationResult(outputContent, true, truncatedBy, totalLines, totalBytes,
            outputLines.size(), finalOutputBytes, lastLinePartial, false, maxLines, maxBytes);
    }

    private static String[] splitLines(String content) {
        if (content.isEmpty()) return new String[0];
        String[] lines = content.split("\n", -1);
        if (content.endsWith("\n")) {
            String[] trimmed = new String[lines.length - 1];
            System.arraycopy(lines, 0, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return lines;
    }

    private static String truncateStringToBytesFromEnd(String str, long maxBytes) {
        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) return str;
        int start = bytes.length - (int) maxBytes;
        // Find valid UTF-8 boundary
        while (start < bytes.length && (bytes[start] & 0xC0) == 0x80) start++;
        return new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8);
    }

    /**
     * Keep the start and the end of {@code content}, half of {@code maxBytes} each, and replace
     * the middle with a {@code …N chars truncated…} marker, like Codex does for tool output.
     * Cuts only at character boundaries ({@code truncate.ts:288-314}).
     *
     * <p>MCP tool results use this at 20 KiB; the head/tail limits above are a separate budget and
     * the two must not be folded together.</p>
     *
     * @param content the text to limit
     * @param maxBytes the byte budget, UTF-8
     * @return the limited text plus what was left out
     */
    public static MiddleTruncationResult truncateMiddle(String content, long maxBytes) {
        var buf = content.getBytes(StandardCharsets.UTF_8);
        int totalLines = splitLines(content).length;
        if (buf.length <= maxBytes) {
            return new MiddleTruncationResult(content, false, 0, buf.length, totalLines);
        }
        // Continuation bytes (10xxxxxx) are not character starts.
        int headEnd = (int) (maxBytes / 2);
        while (headEnd > 0 && !isBoundary(buf, headEnd)) {
            headEnd--;
        }
        int tailStart = buf.length - (int) (maxBytes - maxBytes / 2);
        while (tailStart < buf.length && !isBoundary(buf, tailStart)) {
            tailStart++;
        }
        var head = new String(buf, 0, headEnd, StandardCharsets.UTF_8);
        var tail = new String(buf, tailStart, buf.length - tailStart, StandardCharsets.UTF_8);
        var removed = new String(buf, headEnd, tailStart - headEnd, StandardCharsets.UTF_8);
        // pi counts code points (Array.from), not UTF-16 units.
        var removedChars = removed.codePointCount(0, removed.length());
        return new MiddleTruncationResult(
            head + "…" + removedChars + " chars truncated…" + tail,
            true, removedChars, buf.length, totalLines);
    }

    private static boolean isBoundary(byte[] buf, int index) {
        return index >= buf.length || (buf[index] & 0xC0) != 0x80;
    }

    /**
     * What {@link #truncateMiddle} produced ({@code truncate.ts:278-286}).
     *
     * @param content the start and end with the marker between them
     * @param truncated whether anything was left out
     * @param removedChars characters left out, counted as code points
     * @param totalBytes bytes of the original, UTF-8
     * @param totalLines lines of the original
     */
    public record MiddleTruncationResult(
        String content,
        boolean truncated,
        int removedChars,
        int totalBytes,
        int totalLines
    ) {}

    public record TruncationResult(
        String content,
        boolean truncated,
        String truncatedBy,    // "lines" | "bytes" | null
        int totalLines,
        int totalBytes,
        int outputLines,
        int outputBytes,
        boolean lastLinePartial,  // meaningful only for tail truncation (bash)
        boolean firstLineExceedsLimit,
        int maxLines,
        long maxBytes
    ) {}
}
