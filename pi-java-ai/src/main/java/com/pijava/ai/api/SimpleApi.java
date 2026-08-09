package com.pijava.ai.api;

import java.util.List;

import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;

/**
 * Non-streaming convenience API.
 *
 * <p>Sends a prompt and returns the complete response synchronously.
 * Useful for simple one-shot calls where streaming is not needed.</p>
 */
public interface SimpleApi {

    /**
     * Send a prompt and get the full response text.
     *
     * @param model    the model to use
     * @param messages conversation history
     * @param options  API call options
     * @return the complete assistant response text
     */
    String prompt(ModelId<?> model, List<Message> messages, ApiOptions options);
}
