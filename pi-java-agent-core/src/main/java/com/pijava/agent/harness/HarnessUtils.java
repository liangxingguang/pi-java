package com.pijava.agent.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.entry.ProvisionedEntry;
import com.pijava.agent.record.LaneRecord;
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
     * lane's own equivalent of "writing while a run is in flight" is adding to
     * {@code pendingWrites} while the lane is not idle — a run's own output
     * (assistant reply, tool results, mid-run steer, truncation feedback) is
     * deferred, while the prompt that starts the run is a direct append.</p>
     *
     * <p>Lives here rather than in {@link ActionExecutor} because the write
     * sites are spread over four classes (ActionExecutor, AssistantStream-
     * Executor, ToolExecutionPipeline, ContextAssembler).</p>
     *
     * <p>The run id falls back to the empty string: {@code RecordJsonCodec}
     * requires the field, and {@link RecordLogValidator} reads an empty run id
     * as "no run id", skipping the unknown-operation check.</p>
     */
    static void recordDeferredWrite(LaneState lane, Entry entry) {
        if (lane.phase instanceof RunPhase.Idle) {
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

    /**
     * Fail every tool call in the latest assistant message back into the
     * transcript (pi {@code agent-loop.ts:211-214, 381}): the response hit the
     * output token limit, so the calls' arguments may be truncated mid-JSON
     * and must not be executed. The error result is appended as a tool message
     * so the model can re-issue the calls with complete arguments.
     *
     * <p>Called before the run transitions back to {@code ASSISTANT} — the
     * inner loop continues with the model seeing the failure.</p>
     *
     * <p>Lives here rather than in a step executor: its caller is the
     * non-streaming truncation arm of the run loop, and it is the sibling of
     * {@link #extractToolCalls}, which it reads the calls through.</p>
     */
    static void failTruncatedToolCalls(LaneState lane) {
        var toolCalls = extractToolCalls(lane.partial);
        for (var call : toolCalls) {
            var toolEntry = new Entry.Message(
                UUID.randomUUID().toString(), 0, null, null,
                new Message.ToolResultMessage(
                    call.toolCallId(), call.toolName(),
                    List.of(new ContentBlock.TextContent(
                        "Tool call \"" + call.toolName()
                            + "\" was not executed: the response hit the output token limit, "
                            + "so its arguments may be truncated. Re-issue the tool call with "
                            + "complete arguments.")),
                    true),
                null);
            lane.transcript.add(toolEntry);
            lane.pendingWrites.add(toolEntry);
            // Truncation feedback is the run's own output ⇒ deferred (docs/22 D3).
            recordDeferredWrite(lane, toolEntry);
        }
    }
}
