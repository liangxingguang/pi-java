package com.pijava.coding.agent.core;

import java.util.List;

/**
 * {@link AgentSession#clearQueue()} 的结果：被清掉的队列文本（B34，docs/26；
 * pi {@code clearQueue} 返回 {@code {steering, followUp}}，
 * agent-session.ts:2355-2364）。
 */
public record ClearedQueue(List<String> steering, List<String> followUp) {

    /** Defensively copies both lists. */
    public ClearedQueue {
        steering = List.copyOf(steering);
        followUp = List.copyOf(followUp);
    }
}
