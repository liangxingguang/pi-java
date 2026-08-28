package com.pijava.agent.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * Declares the steer/followUp/nextRun queue scheduling API.
 *
 * <p>Phase 3: implements enqueueing, cancellation, and mode-aware draining.
 * Queue consumption happens in {@link ActionExecutor} (steer at the next
 * assistant round, followUp when a run finishes, nextRun when the lane is
 * idle). Package-private — only {@code AgentHarness} delegates to it.</p>
 */
final class QueueManager {

    private final ConcurrentMap<String, LaneState> lanes;
    private final Supplier<QueueMode> steeringMode;
    private final Supplier<QueueMode> followUpMode;

    QueueManager(
            ConcurrentMap<String, LaneState> lanes,
            Supplier<QueueMode> steeringMode,
            Supplier<QueueMode> followUpMode) {
        this.lanes = lanes;
        this.steeringMode = steeringMode;
        this.followUpMode = followUpMode;
    }

    /** Enqueue a steer prompt. Phase 3. */
    String steer(String laneName, String prompt) {
        return steer(laneName, prompt, List.of());
    }

    /** Enqueue a steer prompt with images. */
    String steer(String laneName, String prompt, List<PromptImage> images) {
        var lane = requireLane(laneName);
        synchronized (lane) {
            var item = new LaneInfo.QueuedItem(prompt, images, lane.queueSeq++);
            lane.steerQueue.addLast(item);
            return Long.toString(item.seq());
        }
    }

    /** Enqueue a follow-up prompt. Phase 3. */
    String followUp(String laneName, String prompt) {
        return followUp(laneName, prompt, List.of());
    }

    /** Enqueue a follow-up prompt with images. */
    String followUp(String laneName, String prompt, List<PromptImage> images) {
        var lane = requireLane(laneName);
        synchronized (lane) {
            var item = new LaneInfo.QueuedItem(prompt, images, lane.queueSeq++);
            lane.followUpQueue.addLast(item);
            return Long.toString(item.seq());
        }
    }

    /** Enqueue a next-run prompt. Phase 3. */
    String nextRun(String laneName, String prompt) {
        return nextRun(laneName, prompt, List.of());
    }

    /** Enqueue a next-run prompt with images. */
    String nextRun(String laneName, String prompt, List<PromptImage> images) {
        var lane = requireLane(laneName);
        synchronized (lane) {
            var item = new LaneInfo.QueuedItem(prompt, images, lane.queueSeq++);
            lane.nextRunQueue.addLast(item);
            return Long.toString(item.seq());
        }
    }

    /** Cancel queued items of the given type. Phase 3. */
    void cancelQueued(String laneName, String queueType) {
        var lane = requireLane(laneName);
        synchronized (lane) {
            switch (queueType) {
                case "steer" -> lane.steerQueue.clear();
                case "followUp" -> lane.followUpQueue.clear();
                case "nextRun" -> lane.nextRunQueue.clear();
                default -> throw new IllegalArgumentException(
                    "Unknown queue type: " + queueType
                        + " (expected steer, followUp, or nextRun)");
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
        return HarnessUtils.requireLane(lanes, laneName);
    }
}
