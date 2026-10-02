package com.pijava.ai.protocol;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Pure functions for OpenAI Completions {@code reasoning_details}, ported
 * verbatim from pi {@code openai-completions.ts:130-147/:252-266}.
 *
 * <p>Detail objects come in three shapes — {@code reasoning.summary},
 * {@code reasoning.encrypted}, {@code reasoning.text} — with common optional
 * fields {@code id}/{@code format}/{@code index}. Streamed consecutive
 * text/summary deltas merge into one logical entry; encrypted entries stay
 * discrete.</p>
 */
final class CompletionReasoningDetails {

    private CompletionReasoningDetails() {}

    /** pi {@code isOpenAIReasoningDetail}: object with valid common fields and one known shape. */
    static boolean isDetail(JsonNode candidate) {
        if (candidate == null || !candidate.isObject()) {
            return false;
        }
        JsonNode id = candidate.get("id");
        if (id != null && !id.isNull() && !id.isTextual()) {
            return false;
        }
        // pi: format absent or string (null rejected).
        JsonNode format = candidate.get("format");
        if (format != null && !format.isTextual()) {
            return false;
        }
        // pi: index absent or number (null rejected).
        JsonNode index = candidate.get("index");
        if (index != null && !index.isNumber()) {
            return false;
        }
        return switch (candidate.path("type").asText("")) {
            case "reasoning.summary" -> hasTextual(candidate, "summary");
            case "reasoning.encrypted" -> hasTextual(candidate, "data");
            case "reasoning.text" -> {
                JsonNode signature = candidate.get("signature");
                yield hasTextual(candidate, "text")
                    && (signature == null || signature.isNull() || signature.isTextual());
            }
            default -> false;
        };
    }

    private static boolean hasTextual(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual();
    }

    /**
     * pi {@code appendOpenAIReasoningDetail}: merge adjacent text/summary
     * entries; otherwise append the entry as-is.
     */
    static void append(List<JsonNode> details, JsonNode detail) {
        if (details.isEmpty()) {
            details.add(detail);
            return;
        }
        JsonNode last = details.get(details.size() - 1);
        String lastType = last.path("type").asText("");
        String type = detail.path("type").asText("");
        if ("reasoning.text".equals(type) && "reasoning.text".equals(lastType)) {
            mergeText(details, last, detail);
        } else if ("reasoning.summary".equals(type) && "reasoning.summary".equals(lastType)) {
            mergeSummary(details, last, detail);
        } else {
            details.add(detail);
        }
    }

    private static void mergeText(List<JsonNode> details, JsonNode last, JsonNode detail) {
        var merged = ((ObjectNode) last).deepCopy();
        merged.put("text", last.path("text").asText() + detail.path("text").asText());
        // pi lastDetail.signature ||= detail.signature.
        JsonNode lastSignature = last.get("signature");
        if (lastSignature == null || lastSignature.isNull() || lastSignature.asText().isEmpty()) {
            JsonNode signature = detail.get("signature");
            if (signature != null) {
                merged.set("signature", signature);
            }
        }
        fillMissingCommon(merged, detail);
        details.set(details.size() - 1, merged);
    }

    private static void mergeSummary(List<JsonNode> details, JsonNode last, JsonNode detail) {
        var merged = ((ObjectNode) last).deepCopy();
        merged.put("summary", last.path("summary").asText() + detail.path("summary").asText());
        fillMissingCommon(merged, detail);
        details.set(details.size() - 1, merged);
    }

    /** pi {@code fillMissingCommonReasoningDetailFields}: id ??=, format ||=, index ??=. */
    private static void fillMissingCommon(ObjectNode target, JsonNode source) {
        if (target.get("id") == null) {
            target.set("id", source.get("id"));
        }
        JsonNode format = target.get("format");
        if (format == null || format.asText().isEmpty()) {
            target.set("format", source.get("format"));
        }
        if (target.get("index") == null) {
            target.set("index", source.get("index"));
        }
    }
}
