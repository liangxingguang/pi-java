package com.pijava.ai.api;

import java.util.List;

import com.pijava.ai.message.Message;

/** Immutable ordered transcript context supplied to an AI request. */
public record TranscriptContext(List<Message> messages) {
    /** Defensively copy the supplied ordered messages, treating null as empty. */
    public TranscriptContext {
        messages = List.copyOf(messages == null ? List.of() : messages);
    }
}
