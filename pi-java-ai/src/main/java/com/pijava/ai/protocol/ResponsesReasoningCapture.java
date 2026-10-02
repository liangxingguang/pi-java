package com.pijava.ai.protocol;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.openai.core.ObjectMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseReasoningItem;

import com.pijava.ai.stream.StreamPartialBuilder;

/**
 * Responses reasoning capture + Azure backfill state, extracted from
 * {@link ResponsesStreamProcessor} to keep that file within the file-length
 * limit. Behavior is pi {@code openai-responses-shared.ts:533-549/:686-697}.
 */
final class ResponsesReasoningCapture {

    /** A captured reasoning item: serialized whole-item JSON and its block index. */
    private record ReasoningItemState(String json, int blockIndex) {}

    private final Map<String, ReasoningItemState> items = new HashMap<>();

    /**
     * Capture a reasoning item at output_item.done (pi :686-697): stamp the
     * authoritative visible text (joined summaries, fallback joined content)
     * and the whole item JSON as the block signature; remember it by id.
     */
    void capture(StreamPartialBuilder builder, ResponseReasoningItem reasoning) {
        String summaryText = reasoning.summary().stream()
            .map(ResponseReasoningItem.Summary::text)
            .filter(text -> text != null && !text.isEmpty())
            .collect(Collectors.joining("\n\n"));
        String contentText = reasoning.content().stream().flatMap(List::stream)
            .map(ResponseReasoningItem.Content::text)
            .filter(text -> text != null && !text.isEmpty())
            .collect(Collectors.joining("\n\n"));
        // pi :687 —— summary || content || existing block text (null ⇒ keep).
        String authoritative = !summaryText.isEmpty() ? summaryText
            : !contentText.isEmpty() ? contentText : null;
        String json;
        try {
            json = ObjectMappers.jsonMapper().writeValueAsString(reasoning);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize reasoning item", e);
        }
        builder.stampReasoning(authoritative, json);
        items.put(reasoning.id(),
            new ReasoningItemState(json, builder.thinkingBlockIndex()));
    }

    /**
     * Backfill encrypted_content from the terminal response output
     * (pi :533-549): Azure gives the field only here. Items whose captured
     * JSON already carries it are left alone.
     */
    void backfill(StreamPartialBuilder builder, List<ResponseOutputItem> terminalOutput) {
        for (var outItem : terminalOutput) {
            if (outItem.reasoning().isEmpty()) {
                continue;
            }
            var terminal = outItem.reasoning().get();
            String encrypted = terminal.encryptedContent().orElse(null);
            if (encrypted == null || encrypted.isEmpty()) {
                continue;
            }
            var state = items.get(terminal.id());
            if (state == null) {
                continue;
            }
            JsonNode stored;
            try {
                stored = ObjectMappers.jsonMapper().readTree(state.json());
            } catch (Exception e) {
                throw new IllegalStateException("Failed to parse reasoning signature", e);
            }
            JsonNode existing = stored.get("encrypted_content");
            if (existing != null && !existing.asText().isEmpty()) {
                continue;
            }
            ((ObjectNode) stored).put("encrypted_content", encrypted);
            String merged;
            try {
                merged = ObjectMappers.jsonMapper().writeValueAsString(stored);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to merge reasoning signature", e);
            }
            builder.restampBlockSignature(state.blockIndex(), merged);
            items.put(terminal.id(), new ReasoningItemState(merged, state.blockIndex()));
        }
    }
}
