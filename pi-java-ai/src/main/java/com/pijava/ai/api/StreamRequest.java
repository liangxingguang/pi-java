package com.pijava.ai.api;

import java.util.List;
import java.util.Map;

import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;

/**
 * A streaming chat request sent to an LLM provider.
 *
 * @param model        the model to use
 * @param systemPrompt system instruction ({@code null} = none). Carried separately from
 *                     {@code messages} because pi's {@code Message} union has no system
 *                     role; every provider maps this to its own system field
 * @param messages     conversation history
 * @param tools        tool definitions (may be empty)
 * @param maxTokens    maximum output tokens (-1 for provider default)
 * @param temperature  sampling temperature (-1 for provider default)
 * @param extra        provider-specific parameters
 */
public record StreamRequest(
    ModelId<?> model,
    String systemPrompt,
    List<Message> messages,
    List<ToolDefinition> tools,
    int maxTokens,
    double temperature,
    Map<String, Object> extra
) {
    /** Compact constructor that defensively copies the messages, tools, and extra maps. */
    public StreamRequest {
        messages = List.copyOf(messages);
        tools = List.copyOf(tools);
        extra = Map.copyOf(extra);
    }

    /** Create a simple request with defaults. */
    public static StreamRequest of(ModelId<?> model, List<Message> messages) {
        return new StreamRequest(model, null, messages, List.of(), -1, -1, Map.of());
    }
}
