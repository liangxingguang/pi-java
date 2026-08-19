package com.pijava.coding.agent.core;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 会话级事件广播总线（多监听器）。
 *
 * <p>对齐 pi {@code AgentSession} 的 {@code _eventListeners} + {@code _emit} 模型：
 * 支持多个监听器，{@code subscribe} 返回的句柄关闭时只摘除本监听器，单个监听器
 * 抛异常被隔离不影响广播。从 {@code AgentSession} 抽出以控制文件行数（CLAUDE.md
 * ≤500 行约束）。</p>
 */
final class SessionEventHub {

    private final List<Consumer<AgentSessionEvent>> listeners =
        new CopyOnWriteArrayList<>();

    /** 注册监听器；返回句柄，关闭时只摘除本监听器。 */
    AutoCloseable subscribe(Consumer<AgentSessionEvent> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /** 广播事件给所有监听器。 */
    void emit(AgentSessionEvent event) {
        for (var listener : listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException e) {
                java.util.logging.Logger.getLogger(AgentSession.class.getName())
                    .warning("AgentSessionEvent listener threw: " + e);
            }
        }
    }
}
