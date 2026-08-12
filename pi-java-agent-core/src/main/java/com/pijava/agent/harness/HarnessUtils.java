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

    /** The ID of the most recent entry, or empty string if the lane is empty. */
    static String lastEntryId(LaneState lane) {
        return lane.lastEntry() != null ? lane.lastEntry().header().id() : "";
    }

    static Message toMessage(Entry.Message entry) {
        return switch (entry.role()) {
            case "user" -> new Message.UserMessage(entry.blocks());
            case "assistant" -> new Message.AssistantMessage(entry.blocks());
            case "tool" -> {
                var block = (ContentBlock.ToolResultContent) entry.blocks().get(0);
                yield new Message.ToolResultMessage(
                    block.toolUseId(), block.toolName(),
                    block.content(), block.isError());
            }
            default -> new Message.UserMessage(entry.blocks());
        };
    }

    static LaneState.NewestOwn deriveNewestOwn(LaneState lane) {
        for (int i = lane.transcript.size() - 1; i >= 0; i--) {
            var entry = lane.transcript.get(i);
            if (entry instanceof Entry.Message msg && "assistant".equals(msg.role())) {
                String stopReason = lane.partial != null ? lane.partial.stopReason() : null;
                return new LaneState.NewestOwn(
                    msg.header().id(), "message", "assistant", stopReason);
            }
        }
        return null;
    }

    static boolean isErrorStopReason(String sr) {
        return "error".equals(sr) || "aborted".equals(sr);
    }

    /** Derive the run outcome from the newest own entry's stop reason. */
    static String determineOutcome(LaneState lane) {
        if (lane.newestOwn == null) return "error";
        String sr = lane.newestOwn.stopReason();
        if (isErrorStopReason(sr)) return "error";
        if ("tool_use".equals(sr)) return "tool_use";
        return "completed";
    }

    static String entryTypeName(Entry entry) {
        return switch (entry) {
            case Entry.Message m -> "message";
            case Entry.ModelChange mc -> "model_change";
            case Entry.ThinkingLevelChange tlc -> "thinking_level_change";
            case Entry.ActiveToolsChange atc -> "active_tools_change";
            case Entry.Compaction c -> "compaction";
            case Entry.BranchSummary bs -> "branch_summary";
            case Entry.Custom c -> "custom";
        };
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
