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
