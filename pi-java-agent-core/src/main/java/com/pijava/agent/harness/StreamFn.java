package com.pijava.agent.harness;

import com.pijava.ai.api.StreamIterator;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.stream.StreamEvent;

/**
 * Stream-based LLM call function signature.
 *
 * <p>Contract: never throw exceptions — errors are encoded as
 * {@link StreamEvent.StreamError} in the event stream. Injected into
 * {@link AgentHarness} via {@link HarnessConfig}; callers drive the harness
 * through the public run API and never touch this directly.</p>
 *
 * <p><b>形参与顺序逐字对齐 pi {@code StreamFn}</b>
 * （{@code packages/agent/src/types.ts:28-32}）：{@code (model, context, options)}。
 * 系统提示与工具定义都在 {@link Context} 上，所以这里没有独立的 {@code messages}
 * 或 {@code tools} 形参 —— 它们此前分别以「消息列表」和
 * {@code StreamOptions.tools} 的形式重复出现。</p>
 */
@FunctionalInterface
public interface StreamFn {

    /**
     * Send a streaming request to an LLM.
     *
     * @param model   model identifier
     * @param context pi 的 {@code llmContext}：{@code systemPrompt} / {@code messages} / {@code tools}
     * @param options extra options (thinking config, max tokens, etc.)
     * @return an iterator over stream events (blocking, for virtual threads)
     */
    StreamIterator stream(
        ModelId<?> model,
        Context context,
        StreamOptions options
    );
}
