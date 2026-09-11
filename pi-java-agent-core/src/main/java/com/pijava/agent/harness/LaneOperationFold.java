package com.pijava.agent.harness;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.entry.ProvisionedEntry;
import com.pijava.agent.record.LaneRecord;
import com.pijava.agent.record.OperationOutcome;
import com.pijava.agent.record.QueueKind;
import com.pijava.agent.record.StepKind;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;

/**
 * Pure functions rebuilding a lane's orchestration state from its record log
 * (pi's {@code harness/reducer.ts} {@code reduceLaneState}), returning a
 * {@link LaneStateFolder.FoldedState}.
 *
 * <p>Inputs are a <em>bounded slice</em>, not a full transcript: the caller
 * passes the lane's records plus the entries belonging to the open operation
 * ({@code ownEntries}) and the configuration entries. Both consumers — the
 * sentinel test and resume recovery — read the same log the live driver
 * appends to; the live driver keeps mutating {@link LaneState} in place, so
 * the fold never needs to be incremental.</p>
 *
 * <p>Extracted from {@link LaneStateFolder} to keep files under the 500-line
 * limit; package-private and stateless, like its former host.</p>
 */
final class LaneOperationFold {

    private LaneOperationFold() {}

    /**
     * Fold a lane's bounded record slice back into orchestration state.
     *
     * @param lane                 lane name (for diagnostics)
     * @param records              the lane's records, oldest first
     * @param ownEntries           entries appended by the open operation, oldest first
     * @param configurationEntries configuration entries (see {@link Entry#isConfiguration()}), oldest first
     * @throws RecordLogCorruption when the record log is internally inconsistent
     */
    static LaneStateFolder.FoldedState fold(String lane, List<LaneRecord> records,
                                            List<Entry> ownEntries,
                                            List<Entry> configurationEntries) {
        var ordered = orderBySeq(records);
        RecordLogValidator.validate(lane, ordered);

        var openOp = openOperation(ordered);
        var finished = lastFinish(ordered);
        return new LaneStateFolder.FoldedState(
            lane,
            openOp == null ? RunPhase.IDLE : RunPhase.CHECKPOINT,
            openOp == null ? null : openOp.id(),
            openOp == null ? 0 : stepIndex(ordered, openOp.id()),
            newestOwn(ownEntries),
            finished != null && finished.outcome() == OperationOutcome.FAILED,
            finished != null && finished.outcome() == OperationOutcome.ABORTED,
            effectiveConfiguration(configurationEntries),
            pendingQueue(ordered, QueueKind.STEER),
            pendingQueue(ordered, QueueKind.FOLLOW_UP),
            pendingQueue(ordered, QueueKind.NEXT_RUN));
    }

    /** The operation started without a matching finish, if any. */
    private static LaneRecord.OperationStarted openOperation(List<LaneRecord> ordered) {
        LaneRecord.OperationStarted open = null;
        for (var record : ordered) {
            if (record instanceof LaneRecord.OperationStarted started) {
                open = started;
            } else if (record instanceof LaneRecord.OperationFinished finished
                    && open != null && finished.runId().equals(open.id())) {
                open = null;
            }
        }
        return open;
    }

    /** The most recent finish, or null when no operation ever finished. */
    private static LaneRecord.OperationFinished lastFinish(List<LaneRecord> ordered) {
        LaneRecord.OperationFinished last = null;
        for (var record : ordered) {
            if (record instanceof LaneRecord.OperationFinished finished) {
                last = finished;
            }
        }
        return last;
    }

    /**
     * Assistant steps completed by the operation. Compaction steps are
     * excluded: the live driver's {@code stepIndex} counts LLM rounds only.
     */
    private static int stepIndex(List<LaneRecord> ordered, String runId) {
        int count = 0;
        for (var record : ordered) {
            if (record instanceof LaneRecord.StepAttempt step
                    && step.step() == StepKind.ASSISTANT
                    && runId.equals(step.runId())) {
                count++;
            }
        }
        return count;
    }

    /**
     * The newest assistant message among the operation's own entries, with the
     * stop reason persisted on that entry (docs/23 D1).
     *
     * <p>Type matching rather than a {@code role()} string compare: an
     * assistant transcript entry is always a {@link Message.AssistantMessage},
     * matching the live driver's {@code deriveNewestOwn}. Entries written
     * before the stop reason was persisted decode it as {@code null}.</p>
     */
    private static LaneState.NewestOwn newestOwn(List<Entry> ownEntries) {
        for (int i = ownEntries.size() - 1; i >= 0; i--) {
            if (ownEntries.get(i) instanceof Entry.Message msg
                    && msg.message() instanceof Message.AssistantMessage assistant) {
                return new LaneState.NewestOwn(
                    msg.id(), "message", "assistant", assistant.stopReason());
            }
        }
        return null;
    }

    /** Overlay the configuration entries in sequence order. */
    private static LaneStateFolder.EffectiveConfiguration effectiveConfiguration(
            List<Entry> configurationEntries) {
        ModelId<?> model = null;
        String thinkingLevel = null;
        List<String> activeToolNames = null;
        for (var entry : configurationEntries.stream()
                .sorted(Comparator.comparingLong(Entry::seq)).toList()) {
            switch (entry) {
                case Entry.ModelChange mc -> model = ModelId.of(mc.provider(), mc.modelId());
                case Entry.ThinkingLevelChange tlc -> thinkingLevel = tlc.thinkingLevel();
                case Entry.ActiveToolsChange atc ->
                    activeToolNames = List.copyOf(atc.activeToolNames());
                default -> { }
            }
        }
        return new LaneStateFolder.EffectiveConfiguration(model, thinkingLevel, activeToolNames);
    }

    /**
     * Queue items enqueued but neither cancelled nor consumed, in enqueue
     * order.
     *
     * <p>An item leaves the pending set only via an explicit
     * {@code QueueCancelled} or {@code QueueConsumed} record — including
     * {@code nextRun} items, whose prompt is merged into the run it starts
     * (pi infers this from entry presence instead, which pi-java's merged
     * drain entry cannot express; docs/21 D4/D10).</p>
     */
    private static List<LaneInfo.QueuedItem> pendingQueue(List<LaneRecord> ordered,
                                                          QueueKind kind) {
        Map<String, LaneRecord.QueueEnqueued> pending = new LinkedHashMap<>();
        for (var record : ordered) {
            if (record instanceof LaneRecord.QueueEnqueued enqueued) {
                pending.put(enqueued.target().entry().id(), enqueued);
            } else if (record instanceof LaneRecord.QueueCancelled cancelled) {
                pending.remove(cancelled.entryId());
            } else if (record instanceof LaneRecord.QueueConsumed consumed) {
                consumed.targets().forEach(t -> pending.remove(t.entry().id()));
            }
        }
        List<LaneInfo.QueuedItem> items = new ArrayList<>();
        for (var enqueued : pending.values()) {
            if (enqueued.queue() == kind) {
                var item = toQueuedItem(enqueued.target());
                if (item != null) {
                    items.add(item);
                }
            }
        }
        return items;
    }

    /**
     * Rebuild the queued prompt from its provisioned target. The entry id is
     * the item's queue sequence number (docs/21 R6); targets that are not
     * messages are dropped rather than failing the whole fold.
     */
    private static LaneInfo.QueuedItem toQueuedItem(ProvisionedEntry<?> target) {
        if (!(target.entry() instanceof Entry.Message msg)) {
            return null;
        }
        List<ContentBlock> content = msg.message().content();
        String prompt = content.stream()
            .filter(ContentBlock.TextContent.class::isInstance)
            .map(block -> ((ContentBlock.TextContent) block).text())
            .reduce((left, right) -> left + "\n" + right)
            .orElse("");
        List<PromptImage> images = content.stream()
            .filter(ContentBlock.ImageContent.class::isInstance)
            .map(block -> (ContentBlock.ImageContent) block)
            .map(image -> new PromptImage(image.mediaType(), image.data()))
            .toList();
        long seq;
        try {
            seq = Long.parseLong(target.entry().id());
        } catch (NumberFormatException e) {
            seq = 0;
        }
        return new LaneInfo.QueuedItem(prompt, images, seq);
    }

    /**
     * The run an operation-scoped record belongs to, or {@code null} when it
     * carries none (an idle enqueue, or an abort on a lane with no run).
     */
    static String runIdOf(LaneRecord record) {
        String runId = switch (record) {
            case LaneRecord.OperationFinished r -> r.runId();
            case LaneRecord.AbortRequested r -> r.runId();
            case LaneRecord.StepAttempt r -> r.runId();
            case LaneRecord.ToolStarted r -> r.runId();
            case LaneRecord.ToolFinished r -> r.runId();
            case LaneRecord.QueueEnqueued r -> r.runId();
            case LaneRecord.QueueCancelled r -> r.runId();
            case LaneRecord.QueueConsumed r -> r.runId();
            case LaneRecord.WriteDeferred r -> r.runId();
            case LaneRecord.UsageRecord r -> r.runId();
            case LaneRecord.OperationStarted r -> null;
        };
        return runId == null || runId.isEmpty() ? null : runId;
    }

    /** Stable ordering: persisted records sort by seq, in-memory ones keep order. */
    static List<LaneRecord> orderBySeq(List<LaneRecord> records) {
        return records.stream().sorted(Comparator.comparingLong(LaneRecord::seq)).toList();
    }
}
