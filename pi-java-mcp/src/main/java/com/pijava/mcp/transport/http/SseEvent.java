package com.pijava.mcp.transport.http;

import org.jspecify.annotations.Nullable;

/**
 * One dispatched SSE event.
 *
 * @param event event name; {@code null} means the default {@code "message"}
 * @param data  data lines joined with {@code \n}
 * @param id    optional event id
 */
record SseEvent(@Nullable String event, String data, @Nullable String id) {
}
