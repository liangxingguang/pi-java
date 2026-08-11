package com.pijava.agent.harness;

import java.util.List;

import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;

/**
 * Stream-based LLM call function signature.
 *
 * <p>Contract: never throw exceptions — errors are encoded as
 * {@link StreamEvent.StreamError} in the event stream. Injected into
 * {@link AgentHarness} via {@link HarnessConfig}; the {@code AgentLoop}
 * never touches this directly.</p>
 *
 * <p>Aligned with pi's {@code StreamFn} type.</p>
 */
@FunctionalInterface
public interface StreamFn {

    /**
     * Send a streaming request to an LLM.
     *
     * @param messages  context messages (built by AgentHarness from LaneState)
     * @param model     model identifier
     * @param options   extra options (thinking config, max tokens, etc.)
     * @return an iterator over stream events (blocking, for virtual threads)
     */
    StreamIterator stream(
        List<Message> messages,
        ModelId<?> model,
        StreamOptions options
    );
}
