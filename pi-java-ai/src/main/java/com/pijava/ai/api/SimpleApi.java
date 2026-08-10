package com.pijava.ai.api;

import java.util.List;

import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;

/**
 * Non-streaming convenience API.
 *
 * <p>Sends a complete chat request and returns the full response. Useful for
 * simple one-shot calls where streaming is not needed, or for tests.</p>
 *
 * <p>The primary entry point is {@link #send(StreamRequest, ApiOptions)} which
 * returns the assistant's response as a {@link Message}. The convenience
 * method {@link #prompt(ModelId, List, ApiOptions)} returns just the text
 * content.</p>
 */
public interface SimpleApi {

    /**
     * Send a chat request and get the full assistant response.
     *
     * @param request the chat request (model, messages, tools, etc.)
     * @param options API call options
     * @return the complete assistant response message
     */
    Message send(StreamRequest request, ApiOptions options);

    /**
     * Send a prompt and get the response as plain text.
     *
     * <p>Convenience wrapper around {@link #send(StreamRequest, ApiOptions)}
     * that extracts only the text content from the assistant's response.</p>
     *
     * @param model    the model to use
     * @param messages conversation history
     * @param options  API call options
     * @return the text content of the assistant's response
     */
    default String prompt(ModelId<?> model, List<Message> messages, ApiOptions options) {
        var request = StreamRequest.of(model, messages);
        var response = send(request, options);
        return response.content().stream()
                .filter(c -> c instanceof com.pijava.ai.message.ContentBlock.TextContent)
                .map(c -> ((com.pijava.ai.message.ContentBlock.TextContent) c).text())
                .reduce("", String::concat);
    }
}
