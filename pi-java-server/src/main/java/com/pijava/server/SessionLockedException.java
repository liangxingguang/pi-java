package com.pijava.server;

/**
 * 会话已被其他连接租用 —— 冲突操作直接拒绝（映射为 SESSION_LOCKED），不排队。
 */
public final class SessionLockedException extends RuntimeException {

    /** @param sessionId 被其他连接持有的会话 ID */
    public SessionLockedException(String sessionId) {
        super("Session is locked by another connection: " + sessionId);
    }
}
