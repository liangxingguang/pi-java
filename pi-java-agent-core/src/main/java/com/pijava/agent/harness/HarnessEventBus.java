package com.pijava.agent.harness;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Internal event bus for harness state changes.
 * Not part of public API — used to drive {@code watch()} subscriptions.
 */
final class HarnessEventBus {

    private final List<Consumer<LaneSnapshot>> laneListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<SessionSnapshot>> sessionListeners = new CopyOnWriteArrayList<>();

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
