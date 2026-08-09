package com.pijava.ai.api;

import java.util.List;
import java.util.Map;

import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;

/**
 * A streaming chat request sent to an LLM provider.
 *
 * @param model       the model to use
 * @param messages    conversation history
 * @param tools       tool definitions (may be empty)
 * @param maxTokens   maximum output tokens (-1 for provider default)
 * @param temperature sampling temperature (-1 for provider default)
 * @param extra       provider-specific parameters
 */
public record StreamRequest(
    ModelId<?> model,
    List<Message> messages,
    List<ToolDefinition> tools,
    int maxTokens,
    double temperature,
    Map<String, Object> extra
) {
    public StreamRequest {
        messages = List.copyOf(messages);
        tools = List.copyOf(tools);
        extra = Map.copyOf(extra);
    }

    /** Create a simple request with defaults. */
    public static StreamRequest of(ModelId<?> model, List<Message> messages) {
        return new StreamRequest(model, messages, List.of(), -1, -1, Map.of());
    }
}
