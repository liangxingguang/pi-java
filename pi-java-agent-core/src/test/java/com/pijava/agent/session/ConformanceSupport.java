package com.pijava.agent.session;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.entry.ProvisionedEntry;
import com.pijava.agent.record.LaneRecord;
import com.pijava.agent.record.NewRecord;
import com.pijava.agent.record.OperationOutcome;
import com.pijava.agent.record.QueueKind;
import com.pijava.agent.record.ReplayKind;
import com.pijava.agent.record.StepKind;
import com.pijava.agent.record.UsageCause;
import com.pijava.ai.Usage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

/** Shared factories for conformance assertions. */
public final class ConformanceSupport {

    private ConformanceSupport() {}

    public static ProvisionedEntry<Entry.Message> userMessage(String id, String text) {
        return new ProvisionedEntry<>(new Entry.Message(id, 0, null, null,
            new Message.UserMessage(List.of(new ContentBlock.TextContent(text))), null));
    }

    public static ProvisionedEntry<Entry.Message> assistantMessage(String id, String text) {
        return new ProvisionedEntry<>(new Entry.Message(id, 0, null, null,
            new Message.AssistantMessage(List.of(new ContentBlock.TextContent(text))), null));
    }

    public static ProvisionedEntry<Entry.Message> toolResult(String id, String toolName,
                                                             String output, boolean isError,
                                                             boolean terminate) {
        return new ProvisionedEntry<>(new Entry.Message(id, 0, null, null,
            new Message.ToolResultMessage("call-1", toolName,
                List.of(new ContentBlock.TextContent(output)), isError),
            terminate ? Boolean.TRUE : null));
    }

    public static ProvisionedEntry<Entry.Custom> custom(String id, String customType,
                                                        Map<String, Object> data) {
        return new ProvisionedEntry<>(new Entry.Custom(id, 0, null, null, customType, data));
    }

    public static NewRecord<LaneRecord.OperationStarted> operationStarted(String id, String lane) {
        return new NewRecord<>(new LaneRecord.OperationStarted(id, 0, lane, null, null,
            new LaneRecord.OperationStarted.Run(List.of(), List.of(), null, null)));
    }

    public static NewRecord<LaneRecord.OperationStarted> compactionStarted(String id, String lane) {
        return new NewRecord<>(new LaneRecord.OperationStarted(id, 0, lane, null, null,
            new LaneRecord.OperationStarted.Compaction(null, "entry-0")));
    }

    public static NewRecord<LaneRecord.OperationFinished> operationFinished(String id, String lane,
                                                                            String runId) {
        return new NewRecord<>(new LaneRecord.OperationFinished(id, 0, lane, null,
            runId, OperationOutcome.COMPLETED, null));
    }

    public static NewRecord<LaneRecord.QueueEnqueued> queueEnqueued(String id, String lane,
                                                                    String runId) {
        return new NewRecord<>(new LaneRecord.QueueEnqueued(id, 0, lane, null,
            QueueKind.STEER, runId, userMessage("target-" + id, "queued")));
    }

    public static NewRecord<LaneRecord.QueueCancelled> queueCancelled(String id, String lane,
                                                                      String entryId) {
        return new NewRecord<>(new LaneRecord.QueueCancelled(id, 0, lane, null, null, entryId));
    }

    public static NewRecord<LaneRecord.UsageRecord> usage(String id, String lane, String runId,
                                                          double input, double output) {
        return new NewRecord<>(new LaneRecord.UsageRecord(id, 0, lane, null,
            Usage.of(input, output), UsageCause.ASSISTANT, runId, "entry-1", null, 0, "stop"));
    }

    public static NewRecord<LaneRecord.StepAttempt> stepAttempt(String id, String lane,
                                                                String runId) {
        return new NewRecord<>(new LaneRecord.StepAttempt(id, 0, lane, null,
            runId, StepKind.ASSISTANT, 0, "entry-1", null));
    }

    public static NewRecord<LaneRecord.ToolStarted> toolStarted(String id, String lane,
                                                                String runId) {
        return new NewRecord<>(new LaneRecord.ToolStarted(id, 0, lane, null,
            runId, "asst-1", 0, "call-1", "bash", Map.of("cmd", "ls"),
            "entry-9", ReplayKind.NEVER));
    }

    public static String textOf(Entry entry) {
        if (entry instanceof Entry.Message message
                && !message.message().content().isEmpty()
                && message.message().content().get(0) instanceof ContentBlock.TextContent text) {
            return text.text();
        }
        return null;
    }
}