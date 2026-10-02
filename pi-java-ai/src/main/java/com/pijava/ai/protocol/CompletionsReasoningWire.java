package com.pijava.ai.protocol;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

/**
 * Extraction of OpenAI Completions structured reasoning details, ported from
 * pi {@code openai-completions.ts:215-241/:1301-1309}.
 *
 * <p>Two sources: signed details (a non-empty JSON array previously stamped
 * on a thinking block) and legacy encrypted details (individual encrypted
 * objects carried on tool calls). Signed details win; legacy details are the
 * fallback. Extracted from {@link OpenAICompletionsMessageConverter} to keep
 * that file within the file-length limit.</p>
 */
final class CompletionsReasoningWire {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Raw wire names accepted as a thinking block's signature
     * (pi {@code openai-completions.ts:278}). Deliberately a different list
     * order from the collect-side probes in {@code OpenAICompletionsApi}.
     */
    private static final List<String> REASONING_SIGNATURE_FIELDS =
        List.of("reasoning", "reasoning_content", "reasoning_text");

    private CompletionsReasoningWire() {}

    /** Whether a signature is one of the raw reasoning wire names. */
    static boolean isReasoningField(String signature) {
        return REASONING_SIGNATURE_FIELDS.contains(signature);
    }

    /**
     * pi {@code preservedReasoningDetails} (:1301-1309): signed details from
     * thinking blocks first, legacy details from tool calls second.
     *
     * @return the preserved detail entries; {@code null} when neither source yields any
     */
    static List<JsonNode> preserve(Message.AssistantMessage assistant) {
        for (var block : assistant.content()) {
            if (block instanceof ContentBlock.ThinkingContent thinking) {
                var signed = parseArray(thinking.signature());
                if (signed != null) {
                    return signed;
                }
            }
        }
        var legacy = new ArrayList<JsonNode>();
        for (var block : assistant.content()) {
            if (block instanceof ContentBlock.ToolUseContent toolUse) {
                var detail = parseLegacy(toolUse.thoughtSignature());
                if (detail != null) {
                    legacy.add(detail);
                }
            }
        }
        return legacy.isEmpty() ? null : legacy;
    }

    /**
     * pi {@code parseOpenAIReasoningDetails}: non-empty JSON array whose every
     * entry is a valid reasoning detail.
     */
    static List<JsonNode> parseArray(String signature) {
        if (signature == null || signature.isEmpty()) {
            return null;
        }
        try {
            JsonNode parsed = JSON.readTree(signature);
            if (!parsed.isArray() || parsed.isEmpty()) {
                return null;
            }
            var entries = new ArrayList<JsonNode>(parsed.size());
            for (var entry : parsed) {
                if (!CompletionReasoningDetails.isDetail(entry)) {
                    return null;
                }
                entries.add(entry);
            }
            return entries;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * pi {@code parseLegacyEncryptedReasoningDetail}: a reasoning.encrypted
     * object with non-empty id and data.
     */
    static JsonNode parseLegacy(String signature) {
        if (signature == null || signature.isEmpty()) {
            return null;
        }
        try {
            JsonNode parsed = JSON.readTree(signature);
            if (!CompletionReasoningDetails.isDetail(parsed)) {
                return null;
            }
            if (!"reasoning.encrypted".equals(parsed.path("type").asText())) {
                return null;
            }
            String id = parsed.path("id").asText("");
            String data = parsed.path("data").asText("");
            if (id.isEmpty() || data.isEmpty()) {
                return null;
            }
            return parsed;
        } catch (Exception e) {
            return null;
        }
    }
}
