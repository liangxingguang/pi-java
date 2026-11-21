package com.pijava.ai.api;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * pi {@code api/strict-tool-limits.ts:1-51} 的移植：记住「本会话的 strict 工具被 Claude 判为
 * 超出上限」，该会话后续请求直接以非 strict 发送。
 *
 * <p>pi 的理由（{@code :19-23}）：工具集在一轮对话里几乎不变，再试 strict 只会重复被拒；
 * 而中途切回 strict 会重写工具定义、丢掉 prompt cache。Anthropic 与 Bedrock 共用这份记忆
 * （两者跑的是同一套 Claude 上限）。</p>
 *
 * <p>容量语义逐条：最多记 {@link #MAX_SESSIONS} 个会话，超限丢最旧；无 {@code sessionId}
 * 时不记账也不判定（{@code undefined} 一律 {@code false}）；{@link #clear(String)} 供宿主在
 * 会话结束时释放（pi 走 {@code cleanupSessionResources}）。</p>
 */
public final class StrictToolLimitMemory {

    /** pi {@code MAX_SESSIONS_OVER_STRICT_TOOL_LIMITS}（{@code strict-tool-limits.ts:25}）。 */
    public static final int MAX_SESSIONS = 10_000;

    private static final Set<String> SESSIONS = new LinkedHashSet<>();

    private StrictToolLimitMemory() {
    }

    /**
     * 记录该会话的 strict 工具已超限（pi {@code markStrictToolLimitsExceeded}）：无会话号
     * 直接忽略；超容量丢最旧。
     *
     * @param sessionId 会话号；null 或空串 ≙ pi 的 {@code undefined}
     */
    public static void mark(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return;
        }
        synchronized (SESSIONS) {
            SESSIONS.add(sessionId);
            while (SESSIONS.size() > MAX_SESSIONS) {
                var oldest = SESSIONS.iterator();
                oldest.next();
                oldest.remove();
            }
        }
    }

    /**
     * 该会话此前是否已被判超限（pi {@code hasExceededStrictToolLimits}）。
     *
     * @param sessionId 会话号；null/空串恒 {@code false}
     * @return true 表示该会话应直接发非 strict
     */
    public static boolean exceeded(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return false;
        }
        synchronized (SESSIONS) {
            return SESSIONS.contains(sessionId);
        }
    }

    /**
     * 释放记忆（pi {@code cleanupSessionResource}）：给会话号只清该会话，给 null/空串清空全部。
     *
     * @param sessionId 会话号，或 null/空串表示全部
     */
    public static void clear(String sessionId) {
        synchronized (SESSIONS) {
            if (sessionId == null || sessionId.isEmpty()) {
                SESSIONS.clear();
            } else {
                SESSIONS.remove(sessionId);
            }
        }
    }
}
