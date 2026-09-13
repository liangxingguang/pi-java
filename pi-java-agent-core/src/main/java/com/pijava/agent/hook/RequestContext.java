package com.pijava.agent.hook;

import com.pijava.ai.message.Message;
import java.util.List;

/**
 * Context passed to {@code before_request} hook.
 *
 * <p>{@code systemPrompt} 与 {@code messages} 分开，与 pi 的 {@code Context}
 * 一致：系统提示不属于消息列表（pi 的 {@code Message} 只有 user / assistant /
 * toolResult 三个角色），所以钩子要看到「这次请求到底发了什么」，两样都得给。</p>
 *
 * @param systemPrompt 本次请求的系统提示（{@code null} 表示不发送）
 */
public record RequestContext(String lane, String runId, String systemPrompt,
                             List<Message> messages) {}
