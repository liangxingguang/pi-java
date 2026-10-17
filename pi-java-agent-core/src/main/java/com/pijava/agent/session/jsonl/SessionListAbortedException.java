package com.pijava.agent.session.jsonl;

/**
 * 渐进会话列表被 {@link com.pijava.ai.AbortSignal} 中止（pi 的
 * {@code throwIfAborted} 对位，session-manager.ts:911,:946）。
 */
public final class SessionListAbortedException extends RuntimeException {

    /** 创建中止异常。 */
    public SessionListAbortedException() {
        super("Session list aborted");
    }
}
