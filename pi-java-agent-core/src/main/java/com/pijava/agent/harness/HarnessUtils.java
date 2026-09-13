package com.pijava.agent.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.entry.ProvisionedEntry;
import com.pijava.agent.record.LaneRecord;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

/**
 * Static helpers shared across the harness execution classes.
 *
 * <p>Package-private — extracted from {@code AgentHarness} in Phase 2c to
 * keep individual files under the 500-line limit.</p>
 */
final class HarnessUtils {

    private HarnessUtils() {}

    /** Look up a lane by name, throwing if absent. */
    static LaneState requireLane(ConcurrentMap<String, LaneState> lanes, String laneName) {
        var lane = lanes.get(laneName);
        if (lane == null) {
            throw new IllegalArgumentException("Lane not found: " + laneName);
        }
        return lane;
    }

    /** The ID of the most recent entry, or {@code null} if the lane is empty. */
    static String lastEntryId(LaneState lane) {
        return lane.lastEntry() != null ? lane.lastEntry().id() : null;
    }

    /** Build a user message: text first, then images (pi agent.ts:402-406 order). */
    static Message buildUserMessage(String prompt, List<PromptImage> images) {
        var content = new ArrayList<ContentBlock>();
        content.add(new ContentBlock.TextContent(prompt));
        if (images != null) {
            images.forEach(img -> content.add(img.toContentBlock()));
        }
        return new Message.UserMessage(content);
    }

    /**
     * Provision a queued item as a placeholder entry for queue records
     * ({@code QueueEnqueued} target / {@code QueueConsumed} targets).
     *
     * <p>The id is the item's queue sequence number, not a real transcript
     * entry id: a drain merges every item into one entry, so an item never
     * maps 1:1 onto a transcript entry (docs/21 R6). It only needs to be
     * stable so a cancel/consume record can reference the same item.</p>
     */
    static ProvisionedEntry<?> provisionedQueueTarget(LaneInfo.QueuedItem item) {
        return new ProvisionedEntry<>(new Entry.Message(
            Long.toString(item.seq()), 0, null, null,
            buildUserMessage(item.prompt(), item.images()), null));
    }

    static LaneState.NewestOwn deriveNewestOwn(LaneState lane) {
        for (int i = lane.transcript.size() - 1; i >= 0; i--) {
            var entry = lane.transcript.get(i);
            if (entry instanceof Entry.Message msg && "assistant".equals(msg.message().role())) {
                String stopReason = lane.partial != null ? lane.partial.stopReason() : null;
                return new LaneState.NewestOwn(
                    msg.id(), "message", "assistant", stopReason);
            }
        }
        return null;
    }

    /**
     * Record an in-flight write as deferred (docs/22 D3).
     *
     * <p>pi's rule is "a lane-view entry write during a run becomes a durable
     * deferred write; while idle it appends" (docs/harness-v2.md:1894). The
     * lane's equivalent of "writing while a run is in flight" is "the lane has
     * an {@link LaneState#activeRun}" — a run's own output (assistant reply,
     * tool results, mid-run steer) is deferred, while the prompt that starts the
     * run is a direct append.</p>
     *
     * <p>The run id falls back to the empty string: {@code RecordJsonCodec}
     * requires the field, and an empty run id reads as "no run id" — a record
     * emitted while idle belongs to no operation.</p>
     */
    static void recordDeferredWrite(LaneState lane, Entry entry) {
        if (!lane.isRunning()) {
            return;
        }
        lane.records.add(new LaneRecord.WriteDeferred(
            UUID.randomUUID().toString(), 0, lane.laneName, null,
            lane.runId == null ? "" : lane.runId,
            new ProvisionedEntry<>(entry)));
    }

    static boolean isErrorStopReason(String sr) {
        return "error".equals(sr) || "aborted".equals(sr);
    }

    /**
     * Derive the run outcome from the newest own entry's stop reason.
     *
     * <p>An aborted turn keeps its own outcome (pi alignment): folding it into
     * {@code "error"} would mark the operation FAILED and pollute the lane's
     * {@code faulted} flag with a user-initiated stop.</p>
     *
     * <p><b>这是「运行」的结局，不是「消息」的结局。</b> {@code tool_use} 与 {@code length}
     * 是消息级的未竟状态：到达终局的运行必然已经把工具调用全部消费完（{@code length}
     * 的调用则由 {@code PiLoopTools} 就地失败掉），因此两者都落 {@code "completed"}。
     * 把它们原样当成运行结局，会让 span 的 {@code outcome} 属性变成 {@code "tool_use"}
     * —— 而 {@link RunLifecycle#outcome} 只认 {@code OperationOutcome} 的取值，
     * 落到默认分支同样是 COMPLETED，两个记录因此自相矛盾。</p>
     */
    static String determineOutcome(LaneState lane) {
        if (lane.newestOwn == null) return "error";
        String sr = lane.newestOwn.stopReason();
        if ("aborted".equals(sr)) return "aborted";
        if (isErrorStopReason(sr)) return "error";
        return "completed";
    }
}
