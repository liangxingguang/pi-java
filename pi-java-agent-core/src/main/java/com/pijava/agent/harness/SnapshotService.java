package com.pijava.agent.harness;

import java.util.List;
import java.util.Set;
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

    private final LaneState lane;
    private final HarnessEventBus eventBus;
    private final ExecutionContext.TokenCounter tokenCounter;
    private final Supplier<String> modelName;
    private final Supplier<Set<String>> activeToolNames;

    SnapshotService(
            LaneState lane,
            HarnessEventBus eventBus,
            ExecutionContext.TokenCounter tokenCounter,
            Supplier<String> modelName,
            Supplier<Set<String>> activeToolNames) {
        this.lane = lane;
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
        if (lane.laneName.equals(laneName)) {
            eventBus.publishLane(buildLaneSnapshot(lane));
        }
        eventBus.publishSession(buildSessionSnapshot());
    }

    private LaneSnapshot buildLaneSnapshot(LaneState lane) {
        // 「是否在跑」由 activeRun 的有无表达（pi this.activeRun !== undefined）。
        LaneInfo.OperationInfo op = lane.isRunning()
            ? new LaneInfo.OperationInfo(lane.runId, "run", "running")
            : null;
        boolean faulted = lane.records.stream()
            .anyMatch(r -> r instanceof LaneRecord.OperationFinished f
                && f.outcome() == com.pijava.agent.record.OperationOutcome.FAILED);
        return new LaneSnapshot(
            lane.laneName,
            List.copyOf(lane.transcript),
            List.copyOf(lane.records),
            lane.lastEntry() != null ? lane.lastEntry().id() : null,
            op,
            lane.queueSnapshot(),
            faulted
        );
    }

    private SessionSnapshot buildSessionSnapshot() {
        // 一个 harness 恰好一条车道（docs/31 §4.3），会话快照的车道列表因此恒为单元素 ——
        // 形状保留，因为会话层的分支是**会话**而不是车道（存储层 lane 才是分支模型）。
        var laneInfos = List.of(new LaneInfo(lane.laneName,
            lane.lastEntry() != null ? lane.lastEntry().id() : null,
            null));
        String phase = lane.isRunning() ? "running" : "idle";
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
        return HarnessUtils.requireLane(lane, laneName);
    }
}
