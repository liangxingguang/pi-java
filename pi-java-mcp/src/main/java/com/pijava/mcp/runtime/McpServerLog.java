package com.pijava.mcp.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pijava.mcp.McpJson;

/**
 * Log messages MCP servers send with {@code notifications/message}, appended to one file
 * (pi {@code log.ts}).
 *
 * <p>Several processes may write to the same file, so every message is one append. The file
 * is rotated to {@code <path>.1} once it grows past {@value #MAX_LOG_BYTES} bytes. Write
 * errors are ignored: logging must not break tools.</p>
 */
public final class McpServerLog {

    /** 5 MiB ({@code log.ts:10}). */
    public static final long MAX_LOG_BYTES = 5L * 1024 * 1024;

    /** {@code Date.toISOString()} always writes three fractional digits. */
    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
                    .withZone(ZoneOffset.UTC);

    private final Path path;

    /** Cached file size; {@code null} until the first write stats the file ({@code log.ts:37}). */
    private @Nullable Long size;

    /**
     * Create a logger writing to {@code path}.
     *
     * @param path the log file, usually {@code <agent-dir>/mcp.log}
     */
    public McpServerLog(Path path) {
        this.path = path;
    }

    /** The file written to. */
    public Path path() {
        return path;
    }

    /**
     * Format one {@code notifications/message} from {@code server} as a log line; continuation
     * lines are indented ({@code log.ts:26-32}).
     *
     * @param server the MCP server name
     * @param params the notification params; anything but an object is wrapped as {@code {data}}
     * @param now the timestamp to stamp
     * @return the line, ending in {@code "\n"}
     */
    public static String formatMessage(String server, @Nullable Object params, Instant now) {
        var message = params instanceof ObjectNode object ? object : wrap(params);
        var level = text(message.get("level"), "info");
        var logger = text(message.get("logger"), "");
        var text = formatData(message.get("data")).replaceAll("\r?\n", "\n    ");
        return ISO.format(now) + " [" + server + "] " + level
                + (logger.isEmpty() ? "" : " " + logger + ":") + " " + text + "\n";
    }

    /** Wrap a non-object params the way {@code { data: params }} does ({@code log.ts:27}). */
    private static ObjectNode wrap(@Nullable Object params) {
        var message = McpJson.mapper().createObjectNode();
        if (params != null) {
            message.set("data", McpJson.mapper().valueToTree(params));
        }
        return message;
    }

    /** A string field, or {@code fallback} when it is absent or not a string. */
    private static String text(@Nullable JsonNode node, String fallback) {
        return node != null && node.isTextual() ? node.textValue() : fallback;
    }

    /**
     * {@code formatData} ({@code log.ts:16-23}): strings verbatim, everything else
     * {@code JSON.stringify}—{@code undefined} when the field is absent, {@code null} for a
     * JSON null, because JS falls through to {@code String(value)}.
     */
    private static String formatData(@Nullable JsonNode data) {
        if (data == null) {
            return "undefined";
        }
        if (data.isTextual()) {
            return data.textValue();
        }
        return data.toString();
    }

    /**
     * Append one message. Any failure is ignored ({@code log.ts:43-60}).
     *
     * @param server the MCP server name
     * @param params the notification params
     */
    public void write(String server, @Nullable Object params) {
        var line = formatMessage(server, params, Instant.now());
        try {
            if (size == null) {
                var parent = path.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                size = currentSize();
            }
            if (size > MAX_LOG_BYTES) {
                // Another process may have rotated it already; check before renaming.
                if (currentSize() > MAX_LOG_BYTES) {
                    Files.move(path, Path.of(path + ".1"), StandardCopyOption.REPLACE_EXISTING);
                }
                size = currentSize();
            }
            Files.writeString(path, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            size += line.getBytes(StandardCharsets.UTF_8).length;
        } catch (IOException | RuntimeException ignored) {
            // Ignore: the log is best effort.
        }
    }

    private long currentSize() {
        try {
            return Files.size(path);
        } catch (IOException expected) {
            return 0;
        }
    }
}
