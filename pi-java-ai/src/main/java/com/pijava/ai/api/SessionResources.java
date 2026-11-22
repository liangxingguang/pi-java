package com.pijava.ai.api;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * pi {@code packages/ai/src/session-resources.ts}：会话级 provider 资源的清理注册表。
 *
 * <p><b>术语：session resource cleanup（会话资源清理）</b>——某些 provider 在进程内按
 * {@code sessionId} 持有状态（Anthropic message thread、strict 超限记忆、Codex WebSocket）。
 * 宿主在会话结束／替换时调用 {@link #cleanup(String)}，一次释放该会话的全部进程内状态。</p>
 *
 * <p>对位 pi：{@code registerSessionResourceCleanup()}（{@code :5-11}）与
 * {@code cleanupSessionResources()}（{@code :12-25}）。注册者：Anthropic threads
 * （{@code anthropic-messages.ts:304-306}）与 strict 限额（{@code strict-tool-limits.ts:48-51}）；
 * 调用点：{@code coding-agent/src/core/agent-session.ts:1501} 的 {@code dispose()}。</p>
 *
 * <p>语义：{@code sessionId} 为 {@code null} 或空串 ⇒ 清空全部（pi 的
 * {@code if (sessionId) ... else clear()}）；单个清理抛错不阻断其余，全部结束后聚合抛出
 * （pi 的 {@code AggregateError}）。</p>
 */
public final class SessionResources {

    /** pi {@code SessionResourceCleanup}（{@code :1}）。 */
    @FunctionalInterface
    public interface SessionResourceCleanup {
        /**
         * 释放一个会话的进程内状态。
         *
         * @param sessionId 会话号；{@code null}／空串表示清空全部
         */
        void cleanup(String sessionId);
    }

    private static final Set<SessionResourceCleanup> CLEANUPS = ConcurrentHashMap.newKeySet();

    private SessionResources() {
    }

    /**
     * pi {@code registerSessionResourceCleanup}：登记一个清理钩子，返回注销句柄
     * （pi 返回的 unsubscribe 函数）。
     */
    public static Runnable register(SessionResourceCleanup cleanup) {
        CLEANUPS.add(cleanup);
        return () -> CLEANUPS.remove(cleanup);
    }

    /**
     * pi {@code cleanupSessionResources(sessionId)}：逐个调用已登记的钩子。
     *
     * <p>某个钩子抛错不会阻断其余钩子；全部执行完后以
     * {@link IllegalStateException}（{@code "Failed to cleanup session resources"}，
     * 首个错误为 cause、其余为 suppressed）抛出。</p>
     */
    public static void cleanup(String sessionId) {
        Throwable first = null;
        for (var cleanup : CLEANUPS) {
            try {
                cleanup.cleanup(sessionId);
            } catch (RuntimeException error) {
                if (first == null) {
                    first = error;
                } else {
                    first.addSuppressed(error);
                }
            }
        }
        if (first != null) {
            throw new IllegalStateException("Failed to cleanup session resources", first);
        }
    }

    /** 无参会话号：清空全部（pi {@code cleanupSessionResources()}）。 */
    public static void cleanup() {
        cleanup(null);
    }
}
