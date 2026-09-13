package com.pijava.agent.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import com.pijava.agent.record.LaneRecord;
import com.pijava.agent.record.QueueKind;

/**
 * Declares the steer/followUp/nextRun queue scheduling API.
 *
 * <p>Phase 3: implements enqueueing, cancellation, and mode-aware draining.
 * Queue consumption happens in {@link PiLaneEngine} (steer at the next
 * assistant round, followUp when a run finishes, nextRun when the lane is
 * idle). Package-private — only {@code AgentHarness} delegates to it.</p>
 */
final class QueueManager {

    private final LaneState lane;
    private final Supplier<QueueMode> steeringMode;
    private final Supplier<QueueMode> followUpMode;

    QueueManager(
            LaneState lane,
            Supplier<QueueMode> steeringMode,
            Supplier<QueueMode> followUpMode) {
        this.lane = lane;
        this.steeringMode = steeringMode;
        this.followUpMode = followUpMode;
    }

    /** Enqueue a steer prompt. Phase 3. */
    String steer(String laneName, String prompt) {
        return steer(laneName, prompt, List.of());
    }

    /** Enqueue a steer prompt with images. */
    String steer(String laneName, String prompt, List<PromptImage> images) {
        return enqueue(laneName, prompt, images, target -> target.steerQueue, QueueKind.STEER);
    }

    /** Enqueue a follow-up prompt. Phase 3. */
    String followUp(String laneName, String prompt) {
        return followUp(laneName, prompt, List.of());
    }

    /** Enqueue a follow-up prompt with images. */
    String followUp(String laneName, String prompt, List<PromptImage> images) {
        return enqueue(laneName, prompt, images, target -> target.followUpQueue,
            QueueKind.FOLLOW_UP);
    }

    /** Enqueue a next-run prompt. Phase 3. */
    String nextRun(String laneName, String prompt) {
        return nextRun(laneName, prompt, List.of());
    }

    /** Enqueue a next-run prompt with images. */
    String nextRun(String laneName, String prompt, List<PromptImage> images) {
        return enqueue(laneName, prompt, images, target -> target.nextRunQueue,
            QueueKind.NEXT_RUN);
    }

    /**
     * Append an item to the selected queue and emit its {@code QueueEnqueued}
     * record (docs/21 D2 — the record log is the audit source, so every
     * enqueue must be recorded).
     */
    private String enqueue(String laneName, String prompt, List<PromptImage> images,
                           QueueAccessor accessor, QueueKind kind) {
        var lane = requireLane(laneName);
        synchronized (lane) {
            var item = new LaneInfo.QueuedItem(prompt, images, lane.queueSeq++);
            accessor.queueOf(lane).addLast(item);
            lane.records.add(new LaneRecord.QueueEnqueued(
                UUID.randomUUID().toString(), 0, laneName, null, kind,
                currentRunId(lane), HarnessUtils.provisionedQueueTarget(item)));
            return Long.toString(item.seq());
        }
    }

    /**
     * The run an enqueue belongs to, or {@code null} while the lane is idle.
     *
     * <p>{@code lane.runId} survives a finished run, so tagging an idle
     * enqueue with it would place the record after that operation's finish.
     * Nothing validates that any more (the record-log fold was retired,
     * docs/30), but the log is still an audit trail worth keeping honest.</p>
     */
    private static String currentRunId(LaneState lane) {
        return lane.isRunning() ? lane.runId : null;
    }

    /** Cancel queued items of the given type. Phase 3. */
    void cancelQueued(String laneName, String queueType) {
        var lane = requireLane(laneName);
        synchronized (lane) {
            var queue = switch (queueType) {
                case "steer" -> lane.steerQueue;
                case "followUp" -> lane.followUpQueue;
                case "nextRun" -> lane.nextRunQueue;
                default -> throw new IllegalArgumentException(
                    "Unknown queue type: " + queueType
                        + " (expected steer, followUp, or nextRun)");
            };
            while (!queue.isEmpty()) {
                lane.records.add(new LaneRecord.QueueCancelled(
                    UUID.randomUUID().toString(), 0, laneName, null, currentRunId(lane),
                    Long.toString(queue.removeFirst().seq())));
            }
        }
    }

    /**
     * Drain the steer queue according to the steering mode.
     * Returns the items to inject as user messages.
     */
    List<LaneInfo.QueuedItem> drainSteer(String laneName) {
        return drain(laneName, lane -> lane.steerQueue, steeringMode.get());
    }

    /**
     * Drain the follow-up queue according to the follow-up mode.
     * Returns the items that start the next run.
     */
    List<LaneInfo.QueuedItem> drainFollowUp(String laneName) {
        return drain(laneName, lane -> lane.followUpQueue, followUpMode.get());
    }

    /**
     * Drain the next-run queue according to the follow-up mode
     * (nextRun has no dedicated setting; it reuses the follow-up mode).
     */
    List<LaneInfo.QueuedItem> drainNextRun(String laneName) {
        return drain(laneName, lane -> lane.nextRunQueue, followUpMode.get());
    }

    private interface QueueAccessor {
        java.util.ArrayDeque<LaneInfo.QueuedItem> queueOf(LaneState lane);
    }

    private List<LaneInfo.QueuedItem> drain(String laneName, QueueAccessor accessor, QueueMode mode) {
        var lane = requireLane(laneName);
        var drained = new ArrayList<LaneInfo.QueuedItem>();
        synchronized (lane) {
            var queue = accessor.queueOf(lane);
            if (mode instanceof QueueMode.All) {
                while (!queue.isEmpty()) {
                    drained.add(queue.removeFirst());
                }
            } else {
                if (!queue.isEmpty()) {
                    drained.add(queue.removeFirst());
                }
            }
        }
        return drained;
    }

    /** True when any queue still contains pending items. */
    boolean hasPending(String laneName) {
        var lane = requireLane(laneName);
        synchronized (lane) {
            return !lane.steerQueue.isEmpty()
                || !lane.followUpQueue.isEmpty()
                || !lane.nextRunQueue.isEmpty();
        }
    }

    private LaneState requireLane(String laneName) {
        return HarnessUtils.requireLane(lane, laneName);
    }
}
