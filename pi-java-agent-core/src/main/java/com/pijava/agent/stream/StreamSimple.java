package com.pijava.agent.stream;

import java.util.List;

import com.pijava.agent.context.ContextEstimator;
import com.pijava.agent.harness.Context;
import com.pijava.agent.harness.StreamFn;
import com.pijava.agent.harness.StreamOptions;
import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.message.AssistantMessage;
import com.pijava.ai.message.Message;
import com.pijava.ai.stream.StreamEvent;
import com.pijava.ai.thinking.ModelThinkingLevel;

/**
 * Convenience wrapper around {@link StreamFn} that automatically handles
 * thinking-level passthrough and context-overflow pre-detection.
 *
 * <p>Aligned with pi's {@code streamSimple()}. Phase 2a responsibilities:
 * <ol>
 *   <li>Pass {@link ModelThinkingLevel} down as {@code reasoning}（<b>不翻译</b> ——
 *       翻译归车道，见 {@link com.pijava.agent.harness.StreamOptions}）</li>
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
     * @param context   request context (systemPrompt / messages / tools)
     * @param reasoning the requested thinking level
     * @param streamFn  the raw stream function
     * @return an iterator over stream events
     */
    public static StreamIterator stream(
            ModelInfo model,
            Context context,
            ModelThinkingLevel reasoning,
            StreamFn streamFn) {

        var messages = context.messages();

        // 1. Pass the level down untranslated（pi agent.ts:465：off ⇒ 不传）
        var reasoningOption = switch (reasoning) {
            case ModelThinkingLevel.Off o -> java.util.Optional.<com.pijava.ai.thinking.ThinkingLevel>empty();
            case ModelThinkingLevel.Enabled e -> java.util.Optional.of(e.level());
        };

        // 2. Pre-check for context overflow
        int overflow = ContextEstimator.checkOverflow(
                messages, model.maxInputTokens());
        if (overflow > 0) {
            // Phase 2a: just report. Phase 2c: trigger compaction.
            // Return error event signalling overflow
            var partial = AssistantMessage.empty()
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
        // 溢出判定不在请求路径上（pi 亦如此，3c/docs/31 §8.21）：它在运行收口后的
        // PostRunCompactionCheck 里，读终局助手消息经 ai 层的 ContextOverflow 判定。
        var options = new StreamOptions(
                java.util.OptionalInt.empty(),
                java.util.OptionalDouble.empty(),
                reasoningOption
        );

        return streamFn.stream(model.id(), context, options);
    }
}
