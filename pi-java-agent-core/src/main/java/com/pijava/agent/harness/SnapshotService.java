package com.pijava.agent.harness;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.pijava.agent.record.LaneRecord;

/**
 * Builds lane/session snapshots and drives snapshot subscriptions.
 *
 * <p>Package-private — only {@code AgentHarness} creates this. Extracted
 * in Phase 2c to keep {@code AgentHarness} under the 500-line limit.</p>
 */
final class SnapshotService {

    private final ConcurrentMap<String, LaneState> lanes;
    private final HarnessEventBus eventBus;
    private final ExecutionContext.TokenCounter tokenCounter;
    private final Supplier<String> modelName;
    private final Supplier<Set<String>> activeToolNames;

    SnapshotService(
            ConcurrentMap<String, LaneState> lanes,
            HarnessEventBus eventBus,
            ExecutionContext.TokenCounter tokenCounter,
            Supplier<String> modelName,
            Supplier<Set<String>> activeToolNames) {
        this.lanes = lanes;
        this.eventBus = eventBus;
        this.tokenCounter = tokenCounter;
        this.modelName = modelName;
        this.activeToolNames = activeToolNames;
    }

    LaneSnapshot snapshot(String laneName) {
        return buildLaneSnapshot(requireLane(laneName));
    }

    WatchHandle<LaneSnapshot> watch(String laneName) {
        var handle = new DefaultWatchHandle<LaneSnapshot>(
            () -> buildLaneSnapshot(requireLane(laneName)));
        Consumer<LaneSnapshot> listener = snapshot -> {
            if (snapshot.lane().equals(laneName)) {
                handle.notify(snapshot);
            }
        };
        eventBus.subscribeLane(listener);
        handle.onClose(() -> eventBus.unsubscribeLane(listener));
        return handle;
    }

    WatchHandle<SessionSnapshot> watchSession() {
        var handle = new DefaultWatchHandle<SessionSnapshot>(this::buildSessionSnapshot);
        Consumer<SessionSnapshot> listener = handle::notify;
        eventBus.subscribeSession(listener);
        handle.onClose(() -> eventBus.unsubscribeSession(listener));
        return handle;
    }

    /** Publish lane + session snapshots after a state change. */
    void publishState(String laneName) {
        var lane = lanes.get(laneName);
        if (lane != null) {
            eventBus.publishLane(buildLaneSnapshot(lane));
        }
        eventBus.publishSession(buildSessionSnapshot());
    }

    private LaneSnapshot buildLaneSnapshot(LaneState lane) {
        LaneInfo.OperationInfo op = null;
        if (!(lane.phase instanceof RunPhase.Idle)) {
            op = new LaneInfo.OperationInfo(
                lane.runId, "run",
                lane.phase instanceof RunPhase.Checkpoint ? "suspended" : "running");
        }
        boolean faulted = lane.records.stream()
            .anyMatch(r -> r instanceof LaneRecord.OperationFinished f
                && "error".equals(f.status()));
        return new LaneSnapshot(
            lane.laneName,
            List.copyOf(lane.transcript),
            lane.lastEntry() != null ? lane.lastEntry().header().id() : null,
            op,
            lane.queueSnapshot(),
            lane.pendingWrites.stream()
                .filter(pw -> !pw.isWritten()).toList(),
            faulted
        );
    }

    private SessionSnapshot buildSessionSnapshot() {
        var laneInfos = lanes.values().stream()
            .map(l -> new LaneInfo(l.laneName,
                l.lastEntry() != null ? l.lastEntry().header().id() : null,
                null))
            .toList();
        String phase = lanes.values().stream()
            .anyMatch(l -> !(l.phase instanceof RunPhase.Idle)) ? "running" : "idle";
        return new SessionSnapshot(
            "session",
            modelName.get(),
            phase,
            tokenCounter.totalTokens(),
            tokenCounter.turnCount(),
            List.copyOf(activeToolNames.get()),
            laneInfos
        );
    }

    private LaneState requireLane(String laneName) {
        return HarnessUtils.requireLane(lanes, laneName);
    }
}
