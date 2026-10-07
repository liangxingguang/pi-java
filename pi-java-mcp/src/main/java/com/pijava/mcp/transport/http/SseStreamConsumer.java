package com.pijava.mcp.transport.http;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import org.jspecify.annotations.Nullable;

/**
 * Parser for an SSE stream (streamable-http.ts:30-98).
 *
 * <p>Line-oriented: blank line dispatches; {@code data} repeated across lines
 * joins with {@code \n} and byte usage is counted so an unterminated event
 * cannot grow without bound. Remaining data at EOF is dispatched once.</p>
 */
final class SseStreamConsumer {

    /** Sink for events and control fields. */
    interface Handler {

        /** One dispatched event. */
        void onEvent(SseEvent event) throws Exception;

        /** Every {@code id} field, including data-less priming events. */
        default void onId(String id) {
        }

        /** Every valid {@code retry} field, in milliseconds. */
        default void onRetry(long retryMs) {
        }
    }

    private SseStreamConsumer() {
    }

    /** Consume the whole stream, dispatching trailing data at EOF. */
    static void consume(InputStream body, int maxEventBytes, Handler handler) throws Exception {
        var reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
        @Nullable String eventName = null;
        @Nullable String eventId = null;
        var dataLines = new ArrayList<String>();
        long dataBytes = 0;

        String raw;
        while ((raw = reader.readLine()) != null) {
            if (raw.isEmpty()) {
                var dispatch = dispatch(eventName, eventId, dataLines);
                if (dispatch != null) {
                    handler.onEvent(dispatch);
                }
                eventName = null;
                eventId = null;
                dataLines.clear();
                dataBytes = 0;
                continue;
            }
            var line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            if (line.startsWith(":")) {
                continue;
            }
            var colon = line.indexOf(':');
            var field = colon < 0 ? line : line.substring(0, colon);
            var value = colon < 0 ? "" : line.substring(colon + 1);
            if (value.startsWith(" ")) {
                value = value.substring(1);
            }
            switch (field) {
                case "data" -> {
                    dataBytes += value.getBytes(StandardCharsets.UTF_8).length
                            + (dataLines.isEmpty() ? 0 : 1);
                    if (dataBytes > maxEventBytes) {
                        throw new java.io.IOException(
                                "MCP SSE event exceeds " + maxEventBytes + " bytes");
                    }
                    dataLines.add(value);
                }
                case "event" -> eventName = value;
                case "id" -> {
                    if (!value.contains("\u0000")) {
                        eventId = value;
                        handler.onId(value);
                    }
                }
                case "retry" -> {
                    if (value.matches("\\d+")) {
                        handler.onRetry(Long.parseLong(value));
                    }
                }
                default -> { }
            }
        }

        var trailing = dispatch(eventName, eventId, dataLines);
        if (trailing != null) {
            handler.onEvent(trailing);
        }
    }

    private static @Nullable SseEvent dispatch(@Nullable String eventName,
                                                @Nullable String eventId,
                                                ArrayList<String> dataLines) {
        if (dataLines.isEmpty()) {
            return null;
        }
        return new SseEvent(eventName, String.join("\n", dataLines), eventId);
    }
}
