package com.pijava.ai.protocol;

import com.fasterxml.jackson.databind.JsonNode;

import com.openai.core.JsonField;
import com.openai.core.ObjectMappers;

/**
 * docs/69：从 completions 入站 chunk 的 SDK {@code ToolCall} 原始附加属性里
 * 取出 {@code custom} 对象（pi wire 形状：
 * {@code {index?,id?,type:"custom",custom:{name?,input?}}}）。
 *
 * <p>SDK 4.42 的 typed {@code ToolCall} 只有 id/function/type，custom 走
 * {@code _additionalProperties()}。pi 的判据是 {@code toolCall.custom &&
 * !toolCall.function}：custom 与 function 并存时 function 优先。</p>
 */
final class CompletionsCustomChunks {

    /** One custom object on a tool-call chunk; both fields optional. */
    record CustomChunk(String name, String input) {}

    /**
     * Read the raw {@code custom} object off a tool-call chunk.
     *
     * @return the parsed custom chunk, or {@code null} when the chunk carries a
     *         function or no custom object
     */
    static CustomChunk from(
            com.openai.models.chat.completions.ChatCompletionChunk.Choice.Delta.ToolCall tc) {
        if (tc.function().isPresent()) {
            return null;
        }
        JsonField<?> raw = tc._additionalProperties().get("custom");
        if (raw == null) {
            return null;
        }
        JsonNode node = ObjectMappers.jsonMapper().convertValue(raw, JsonNode.class);
        if (!node.isObject()) {
            return null;
        }
        var name = node.path("name").isTextual() ? node.path("name").asText() : null;
        var input = node.path("input").isTextual() ? node.path("input").asText() : null;
        return new CustomChunk(name, input);
    }

    private CompletionsCustomChunks() {}
}
