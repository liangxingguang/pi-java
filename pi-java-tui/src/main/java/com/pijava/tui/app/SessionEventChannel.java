package com.pijava.tui.app;

import java.util.function.Consumer;

import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.AgentSessionEvent;
import com.pijava.tui.util.TuiEventDispatcher;

/**
 * 会话事件 → 渲染线程的订阅通道 —— pi
 * {@code InteractiveMode.subscribeToAgent}（{@code interactive-mode.ts:3159-3163}）的对应物。
 *
 * <p>这是 docs/31 §8.38 的<b>唯一</b>结构性改动：pi-java 的 TUI 此前对会话事件零订阅
 * （三条取数通路 —— 观察者回调 / 快照订阅 / transcript 快照 —— 没有一条经过它）。
 * 事件在生产线程到达，经 {@link TuiEventDispatcher} 转投渲染线程后才交给
 * {@code sink}，与 {@code onEntry}/{@code onStreamEvent} 同纪律：窗口部件只在渲染线程被改。</p>
 */
public final class SessionEventChannel implements AutoCloseable {

    /** 会话事件源 —— {@code AgentSession#subscribe} 的形状（测试可替换）。 */
    @FunctionalInterface
    public interface Source {
        /**
         * 注册一个监听器。
         *
         * @param listener 事件监听器
         * @return 摘除句柄
         */
        AutoCloseable subscribe(Consumer<AgentSessionEvent> listener);
    }

    private final TuiEventDispatcher dispatcher;
    private final Consumer<AgentSessionEvent> sink;
    private AutoCloseable handle;

    /**
     * 绑定「事件 → 渲染线程 → 屏幕」。
     *
     * @param dispatcher 跨线程投递队列
     * @param sink       事件落点；**只在渲染线程**被调用
     */
    public SessionEventChannel(TuiEventDispatcher dispatcher, Consumer<AgentSessionEvent> sink) {
        this.dispatcher = dispatcher;
        this.sink = sink;
    }

    /**
     * 订阅会话事件（先摘旧句柄，切换会话时用同一个通道重接）。
     *
     * @param session 目标会话
     * @param source  事件源；{@code null} 时用 {@code session::subscribe}
     */
    public void open(AgentSession session, Source source) {
        close();
        Source effective = source != null ? source : session::subscribe;
        handle = effective.subscribe(event -> dispatcher.dispatch(() -> sink.accept(event)));
    }

    /** Whether the channel currently holds a live subscription (test hook). */
    public boolean isOpen() {
        return handle != null;
    }

    /** 摘除订阅（幂等）。 */
    @Override
    public void close() {
        if (handle != null) {
            try {
                handle.close();
            } catch (Exception ignored) {
                // 摘除失败无碍：句柄即将被丢弃
            }
            handle = null;
        }
    }
}
