package com.pijava.ai.api;

import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;

/**
 * pi {@code transform-messages.ts:67} 的 {@code normalizeToolCallId?} 形参。
 *
 * <p>三参形状逐字照 pi：{@code (id, model, source) => string}。只有 Responses
 * 车道用得到 {@code source}（{@code openai-responses-shared.ts:168} 的
 * {@code isForeignToolCall}）。</p>
 *
 * <p>⚠️ <b>返回原值是合法结果</b>：pi {@code :138} 只在
 * {@code normalizedId !== toolCall.id} 时才记映射、才换 id（命题 P5）。</p>
 */
@FunctionalInterface
public interface ToolCallIdNormalizer {

    /**
     * 归一一条 toolCall id；{@code id} 合法时**原样返回**（不复制、不改写）。
     *
     * @param id     待归一的 toolCall id（pi 的 {@code toolCall.id}）
     * @param target 请求目标模型（pi 的 {@code model} 形参；{@code modelName()} 是模型名，
     *               对应 pi 的 {@code model.id}）
     * @param source 这条 toolCall 所属的助手消息（pi 的 {@code assistantMsg}）
     * @return 归一后的 id，可与入参相同
     */
    String normalize(String id, ModelId<?> target, Message.AssistantMessage source);
}
