package com.pijava.coding.agent.core;

import java.util.function.BooleanSupplier;

import com.pijava.agent.harness.Context;
import com.pijava.agent.harness.StreamOptions;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;

/**
 * docs/50 §2.5：宿主的一次性模型补全缝（由 {@link SessionWiring} 注入，复用会话的
 * {@code recordingStreamFn}）。扩展面 {@code modelRegistry.complete} 经
 * {@link AgentSession#completeModel} 走此缝，纯读、不追加会话记录。
 */
@FunctionalInterface
interface ModelCompletion {

    Message.AssistantMessage complete(ModelId<?> model, Context context,
                                      StreamOptions options, BooleanSupplier aborted);
}
