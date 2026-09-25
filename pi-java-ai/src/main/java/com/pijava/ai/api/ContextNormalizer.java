package com.pijava.ai.api;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.pijava.ai.message.Message;

/** Converts legacy context fields into an ordered transcript. */
public final class ContextNormalizer {

    private ContextNormalizer() {
    }

    /**
     * Normalize legacy system prompt and tools without changing supplied messages.
     *
     * @param systemPrompt legacy system instruction, if any
     * @param messages ordered messages, treated as empty when null
     * @param tools legacy active tools, treated as empty when null
     * @return immutable ordered transcript context
     */
    public static TranscriptContext normalize(
            String systemPrompt, List<Message> messages, List<ToolDefinition> tools) {
        var suppliedMessages = messages == null ? List.<Message>of() : messages;
        var suppliedTools = tools == null ? List.<ToolDefinition>of() : tools;
        var normalized = new ArrayList<Message>(suppliedMessages.size() + 1);

        if (!suppliedMessages.isEmpty()
                && suppliedMessages.get(0) instanceof Message.SystemMessage) {
            normalized.addAll(suppliedMessages);
            return new TranscriptContext(normalized);
        }

        if ((systemPrompt != null && !systemPrompt.isEmpty()) || !suppliedTools.isEmpty()) {
            normalized.add(new Message.SystemMessage(
                systemPrompt == null ? "" : systemPrompt,
                Instant.EPOCH,
                Map.of(),
                List.copyOf(suppliedTools),
                List.of()));
        }
        normalized.addAll(suppliedMessages);
        return new TranscriptContext(normalized);
    }
}
