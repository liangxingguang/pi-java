package com.pijava.agent.harness;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import com.pijava.ai.stream.StreamEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Internal event bus for harness state changes.
 * Not part of public API — used to drive {@code watch()} subscriptions and the
 * Phase 6 raw-{@link StreamEvent} fan-out (RPC + TUI sharing one harness).
 */
final class HarnessEventBus {

    private static final Logger LOG = LoggerFactory.getLogger(HarnessEventBus.class);

    private final List<Consumer<LaneSnapshot>> laneListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<SessionSnapshot>> sessionListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<StreamEvent>> streamListeners = new CopyOnWriteArrayList<>();

    /** Subscribe a raw-stream listener; closing the handle removes only that listener. */
    AutoCloseable subscribeStream(Consumer<StreamEvent> listener) {
        streamListeners.add(listener);
        return () -> streamListeners.remove(listener);
    }

    /**
     * Broadcast a raw {@link StreamEvent} to every subscriber.
     *
     * <p>同步派发，且**隔离抛异常的监听者** —— 一个坏监听者不能把整轮运行带崩。
     * （pi 会 {@code await} 异步监听者，{@code docs/31 §8.5} 已裁决 pi-java 有意偏离：
     * sink 链保持同步，扩展不许在监听者里阻塞。）</p>
     */
    void broadcastStream(StreamEvent event) {
        for (var listener : streamListeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException e) {
                LOG.warn("StreamEvent listener threw: {}", e.toString());
            }
        }
    }

    /** Publish a lane snapshot to all subscribed listeners. */
    void publishLane(LaneSnapshot snapshot) {
        for (var listener : laneListeners) {
            listener.accept(snapshot);
        }
    }

    /** Publish a session snapshot to all subscribed listeners. */
    void publishSession(SessionSnapshot snapshot) {
        for (var listener : sessionListeners) {
            listener.accept(snapshot);
        }
    }

    /** Subscribe to lane-level snapshots. */
    void subscribeLane(Consumer<LaneSnapshot> listener) {
        laneListeners.add(listener);
    }

    /** Unsubscribe from lane-level snapshots. */
    void unsubscribeLane(Consumer<LaneSnapshot> listener) {
        laneListeners.remove(listener);
    }

    /** Subscribe to session-level snapshots. */
    void subscribeSession(Consumer<SessionSnapshot> listener) {
        sessionListeners.add(listener);
    }

    /** Unsubscribe from session-level snapshots. */
    void unsubscribeSession(Consumer<SessionSnapshot> listener) {
        sessionListeners.remove(listener);
    }
}
