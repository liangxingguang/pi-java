package com.pijava.mcp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * Pagination orchestration for {@link McpClient} (client.ts:339-374).
 */
final class McpClientPages {

    private static final int MAX_PAGES = 1_000;

    private McpClientPages() {
    }

    /** All pages of a paginated method. */
    static CompletableFuture<List<Map<String, Object>>> listAll(
            McpClient client, String method, String key,
            Predicate<Map<String, Object>> isItem, McpRequestOptions options) {
        var result = new CompletableFuture<List<Map<String, Object>>>();
        var all = new ArrayList<Map<String, Object>>();
        var seenCursors = new HashSet<String>();
        Thread.startVirtualThread(() -> {
            String cursor = null;
            for (int pageNumber = 0; pageNumber < MAX_PAGES; pageNumber++) {
                Object params = cursor == null ? null : Map.of("cursor", cursor);
                McpClientValidators.Page page;
                try {
                    var raw = client.await(client.request(method, params, options));
                    page = McpClientValidators.validateListPage(method, key, raw, isItem);
                } catch (Throwable error) {
                    result.completeExceptionally(error);
                    return;
                }
                all.addAll(page.items());
                if (page.nextCursor() == null) {
                    result.complete(all);
                    return;
                }
                if (!seenCursors.add(page.nextCursor())) {
                    result.completeExceptionally(new IllegalStateException(
                            "MCP " + method + " returned duplicate cursor: " + page.nextCursor()));
                    return;
                }
                cursor = page.nextCursor();
            }
            result.completeExceptionally(new IllegalStateException(
                    "MCP " + method + " exceeded " + MAX_PAGES + " pages"));
        });
        return result;
    }
}
