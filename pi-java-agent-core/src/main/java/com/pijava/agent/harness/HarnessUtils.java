package com.pijava.agent.harness;

import java.util.List;
import java.util.concurrent.ConcurrentMap;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.message.AssistantMessage;
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

    static Message toMessage(Entry.Message entry) {
        return entry.message();
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

    static boolean isErrorStopReason(String sr) {
        return "error".equals(sr) || "aborted".equals(sr);
    }

    /**
     * Derive the run outcome from the newest own entry's stop reason.
     *
     * <p>An aborted turn keeps its own outcome (pi alignment): folding it into
     * {@code "error"} would mark the operation FAILED and pollute the lane's
     * {@code faulted} flag with a user-initiated stop.</p>
     */
    static String determineOutcome(LaneState lane) {
        if (lane.newestOwn == null) return "error";
        String sr = lane.newestOwn.stopReason();
        if ("aborted".equals(sr)) return "aborted";
        if (isErrorStopReason(sr)) return "error";
        if ("tool_use".equals(sr)) return "tool_use";
        if ("length".equals(sr)) return "length";
        return "completed";
    }

    /**
     * Whether the assistant's stop reason signals an output-token-limit
     * truncation (pi {@code agent-loop.ts:211-214, 381}). When true, any tool
     * calls in the response must NOT be executed — their arguments may be
     * truncated mid-JSON.
     */
    static boolean isLengthStop(LaneState lane) {
        String sr = lane.partial != null ? lane.partial.stopReason() : null;
        return "length".equals(sr);
    }

    static String entryTypeName(Entry entry) {
        return entry.type();
    }

    static List<Action.ExecuteTool> extractToolCalls(AssistantMessage partial) {
        if (partial == null || partial.content() == null) return List.of();
        return partial.content().stream()
            .filter(ContentBlock.ToolUseContent.class::isInstance)
            .map(b -> {
                var tc = (ContentBlock.ToolUseContent) b;
                return new Action.ExecuteTool(tc.id(), tc.name(), tc.arguments());
            }).toList();
    }
}
