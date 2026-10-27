package com.pijava.coding.agent.extension;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * 批 26（docs/37 T7，E5-28）：pi 扩展间事件总线 {@code EventBus}
 * （core/event-bus.ts:1-33 逐字语义）——string channel 的 emit/on/clear，
 * 供扩展互相通信（与 {@link ExtensionEventBus} 的<b>会话事件</b>面是两件事：
 * 那是规格化事件订阅，这是自由通道）。
 *
 * <ul>
 *   <li>{@code emit} 同步派发；handler 异常吞掉＋记日志（pi 的 console.error
 *       对位），后续 handler 照常；</li>
 *   <li>{@code on} 返回退订闭包（pi 的 {@code () => void} ⇒
 *       {@link AutoCloseable}）；</li>
 *   <li>{@link #clear()} 全清（pi {@code removeAllListeners}）。</li>
 *   <li>{@link #trackedView}：pi 的 {@code trackEventBusSubscription} 对位
 *       （loader.ts:517-522）——经视图注册的订阅进跟踪表，会话失效时由
 *       宿主统一退订（pi runtime.invalidate 的 eventBusUnsubscribers 循环）。</li>
 * </ul>
 */
public final class ExtensionChannelBus {

    private static final Logger LOG = Logger.getLogger(ExtensionChannelBus.class.getName());

    private final Map<String, List<Consumer<Object>>> channels;
    private final Consumer<AutoCloseable> tracker;

    /** 根总线（不跟踪）。 */
    public ExtensionChannelBus() {
        this(new ConcurrentHashMap<>(), null);
    }

    private ExtensionChannelBus(Map<String, List<Consumer<Object>>> channels,
                                Consumer<AutoCloseable> tracker) {
        this.channels = channels;
        this.tracker = tracker;
    }

    /**
     * 派生共享通道表的跟踪视图：经它 {@link #on} 的订阅同时进 {@code tracker}
     * （pi 的 api.events.on 包装，loader.ts:517-522）。
     */
    public ExtensionChannelBus trackedView(Consumer<AutoCloseable> tracker) {
        return new ExtensionChannelBus(channels, tracker);
    }

    /** pi {@code emit(channel, data)}：同步逐个调。 */
    public void emit(String channel, Object data) {
        var handlers = channels.get(channel);
        if (handlers == null) {
            return;
        }
        for (var handler : handlers) {
            try {
                handler.accept(data);
            } catch (RuntimeException e) {
                // pi safeHandler（event-bus.ts:23-27）：异常吞掉续下一个
                LOG.warning("Event handler error (" + channel + "): " + e);
            }
        }
    }

    /** pi {@code on(channel, handler)}：注册序追加；退订按实例移除、空表删键。 */
    public AutoCloseable on(String channel, Consumer<Object> handler) {
        var list = channels.computeIfAbsent(channel, k -> new CopyOnWriteArrayList<>());
        list.add(handler);
        AutoCloseable unsubscribe = () -> {
            list.remove(handler);
            if (list.isEmpty()) {
                channels.remove(channel, list);
            }
        };
        if (tracker != null) {
            tracker.accept(unsubscribe);
        }
        return unsubscribe;
    }

    /** pi {@code clear()}：全清。 */
    public void clear() {
        channels.clear();
    }
}
