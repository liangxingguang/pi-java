package com.pijava.ai.protocol;

import java.util.List;

import com.openai.core.JsonField;

/**
 * Probe a completions delta for the model's reasoning text (extracted from
 * {@code OpenAICompletionsApi} to keep the lane within the file-size limit).
 */
final class CompletionReasoningFields {

    /**
     * A reasoning value found on a delta.
     *
     * @param field the wire name it arrived under. Replayed **verbatim** as the thinking block's
     *              signature (pi {@code openai-completions.ts:615-618}) — that self-description is
     *              the whole point: the replay side reads the signature instead of guessing
     * @param text  the non-empty reasoning text
     */
    record ReasoningField(String field, String text) {}

    /**
     * Wire names probed for reasoning text, **in probe order** — the first non-empty string
     * wins (pi {@code openai-completions.ts:597-620}).
     *
     * <p>⚠️ Do not "unify" this with the replay-side list of accepted signature names
     * ({@code REASONING_SIGNATURE_FIELDS}): pi keeps **two arrays with different orders on
     * purpose**. This one decides which field is read when a relay returns two of them at once
     * (chutes.ai sends both {@code reasoning_content} and {@code reasoning} with the same text
     * ⇒ {@code reasoning_content} wins); the other is only an {@code includes} test, where
     * order cannot matter.</p>
     */
    private static final List<String> REASONING_PROBE_FIELDS =
        List.of("reasoning_content", "reasoning", "reasoning_text");

    /**
     * Find the delta's first **non-empty string** reasoning value (pi {@code :597-620}).
     *
     * <p>pi reads them off the raw delta and guards with {@code typeof value === "string"} —
     * a numeric or object value is not reasoning, which is why {@code JsonField.asString()}
     * (empty for those) is the right accessor.</p>
     *
     * @return the field name and text, or {@code null} when the delta carries no reasoning
     */
    static ReasoningField first(
            com.openai.models.chat.completions.ChatCompletionChunk.Choice.Delta delta) {
        var extra = delta._additionalProperties();
        for (var field : REASONING_PROBE_FIELDS) {
            JsonField<?> raw = extra.get(field);
            if (raw == null) {
                continue;
            }
            var text = raw.asString().orElse(null);
            if (text != null && !text.isEmpty()) {
                return new ReasoningField(field, text);
            }
        }
        return null;
    }

    private CompletionReasoningFields() {}
}
