package com.pijava.agent.stream;

import java.util.List;

import com.pijava.agent.context.ContextEstimator;
import com.pijava.agent.harness.StreamFn;
import com.pijava.agent.harness.StreamOptions;
import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.Message;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ModelThinkingLevel;

/**
 * Convenience wrapper around {@link StreamFn} that automatically handles
 * thinking-level translation and context-overflow pre-detection.
 *
 * <p>Aligned with pi's {@code streamSimple()}. Phase 2a responsibilities:
 * <ol>
 *   <li>Translate {@link ModelThinkingLevel} → provider-specific
 *       {@link com.pijava.ai.thinking.ThinkingConfig} via
 *       {@link ModelInfo#thinkingLevelMap()}</li>
 *   <li>Call {@link ContextEstimator#checkOverflow} before the request</li>
 * </ol>
 *
 * <p>Phase 2c: automatic compaction triggering on overflow.</p>
 */
public final class StreamSimple {

    private StreamSimple() {}

    /**
     * Stream an LLM call with automatic thinking translation and overflow check.
     *
     * @param model     model metadata (includes thinking level map)
     * @param messages  context messages
     * @param reasoning the requested thinking level
     * @param streamFn  the raw stream function
     * @return an iterator over stream events
     */
    public static StreamIterator stream(
            ModelInfo model,
            List<Message> messages,
            ModelThinkingLevel reasoning,
            StreamFn streamFn) {

        // 1. Translate thinking level → provider config
        var thinkingConfig = model.thinkingLevelMap().forLevel(reasoning);

        // 2. Pre-check for context overflow
        int overflow = ContextEstimator.checkOverflow(
                messages, model.maxInputTokens());
        if (overflow > 0) {
            // Phase 2a: just report. Phase 2c: trigger compaction.
            // Return error event signalling overflow
            var partial = com.pijava.ai.message.AssistantMessage.empty()
                    .withStopReason("overflow");
            return StreamIterator.from(List.of(
                    new StreamEvent.Start(partial),
                    new StreamEvent.StreamError("error",
                            new IllegalStateException(
                                    "Context overflow: " + overflow + " messages to compact"),
                            partial)
            ));
        }

        // 3. Build options and call
        // Phase 2c: wrap iterator to run OverflowDetector.isOverflow() post-request
        var options = new StreamOptions(
                java.util.OptionalInt.empty(),
                java.util.OptionalDouble.empty(),
                thinkingConfig,
                List.of()
        );

        return streamFn.stream(messages, model.id(), options);
    }
}
