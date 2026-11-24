package com.pijava.agent.harness;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

import com.pijava.agent.compaction.CompactionSettings;
import com.pijava.agent.context.ContextUsage;
import com.pijava.agent.context.ContextUsageEstimator;
import com.pijava.agent.entry.Entry;
import com.pijava.agent.record.LaneRecord;
import com.pijava.agent.session.ContextEntries;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.model.ModelId;

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
    private final ToIntFunction<ModelId<?>> contextWindow;
    private final Supplier<CompactionSettings> compactionSettings;

    SnapshotService(
            LaneState lane,
            HarnessEventBus eventBus,
            ExecutionContext.TokenCounter tokenCounter,
            Supplier<String> modelName,
            Supplier<Set<String>> activeToolNames,
            ToIntFunction<ModelId<?>> contextWindow,
            Supplier<CompactionSettings> compactionSettings) {
        this.lane = lane;
        this.eventBus = eventBus;
        this.tokenCounter = tokenCounter;
        this.modelName = modelName;
        this.activeToolNames = activeToolNames;
        this.contextWindow = contextWindow;
        this.compactionSettings = compactionSettings;
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
        // 一个 harness 恰好一条车道（原 docs/31 §4.3），会话快照的车道列表因此恒为单元素 ——
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
            laneInfos,
            buildContextUsage(),
            autoCompact()
        );
    }

    /**
     * pi {@code getContextUsage()}（{@code agent-session.ts:4416-4459}）逐条移植：
     * 无模型或窗口 ≤ 0 ⇒ {@code null}；存在最新 compaction 且压缩点之后没有
     * 可信 assistant 用量 ⇒ {@code {tokens:null, percent:null}}；否则按投影估算。
     */
    private ContextUsage buildContextUsage() {
        ModelId<?> model = lane.model;
        if (model == null) {
            return null;
        }
        int window = contextWindow.applyAsInt(model);
        if (window <= 0) {
            return null;
        }
        List<Entry> branch = ContextEntries.pathToLeaf(List.copyOf(lane.transcript), null);
        List<ContextEntries.ProjectedEntry> projected = ContextEntries.projectEntries(branch);
        int compactionIndex = -1;
        for (int i = 0; i < branch.size(); i++) {
            if (branch.get(i) instanceof Entry.Compaction) {
                compactionIndex = i;
            }
        }
        if (compactionIndex >= 0) {
            Set<String> usableUsageEntries = new HashSet<>();
            for (var entry : projected) {
                if (entry.messages().stream().anyMatch(SnapshotService::hasUsableUsage)) {
                    usableUsageEntries.add(entry.source().id());
                }
            }
            boolean hasPostCompactionUsage = false;
            for (int i = compactionIndex + 1; i < branch.size(); i++) {
                if (usableUsageEntries.contains(branch.get(i).id())) {
                    hasPostCompactionUsage = true;
                    break;
                }
            }
            if (!hasPostCompactionUsage) {
                return new ContextUsage(null, window, null);
            }
        }
        ContextUsageEstimator.Estimate estimate =
            ContextUsageEstimator.estimateProjectedContextTokens(projected, branch);
        double tokens = estimate.tokens();
        return new ContextUsage(tokens, window, tokens / window * 100);
    }

    /** pi {@code autoCompactEnabled}（footer 的 {@code (auto)} 指示）。 */
    private boolean autoCompact() {
        if (lane.model == null) {
            return false;
        }
        CompactionSettings settings = compactionSettings.get();
        return settings != null && settings.enabled();
    }

    /** pi {@code getAssistantUsage} 的有效性判据（{@code compaction.ts:167-180}）。 */
    private static boolean hasUsableUsage(Message message) {
        if (message instanceof Message.AssistantMessage assistant && assistant.usage() != null) {
            StopReason stopReason = assistant.stopReason();
            return stopReason != StopReason.ABORTED && stopReason != StopReason.ERROR
                && ContextUsageEstimator.calculateContextTokens(assistant.usage()) > 0;
        }
        return false;
    }

    private LaneState requireLane(String laneName) {
        return HarnessUtils.requireLane(lane, laneName);
    }
}
